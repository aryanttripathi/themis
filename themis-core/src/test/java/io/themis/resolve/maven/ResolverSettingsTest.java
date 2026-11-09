package io.themis.resolve.maven;

import io.themis.model.DependencyGraph;
import io.themis.resolve.ResolutionException;
import io.themis.testing.FixtureRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static io.themis.resolve.maven.MavenDependencyResolverTest.DEFAULTS;
import static io.themis.resolve.maven.MavenDependencyResolverTest.byKey;
import static io.themis.resolve.maven.MavenDependencyResolverTest.keys;
import static io.themis.testing.FixtureRepo.deps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** settings.xml handling, repository security, and resolver session defaults. */
class ResolverSettingsTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
    }

    @AfterEach
    void clearOverrides() {
        System.clearProperty("aether.connector.requestTimeout");
    }

    @Test
    void settingsMirrorIsHonored() {
        repo.publish("com.acme:a:1.0", "jar", "");
        Path app = plainProject(deps("com.acme:a:1.0")); // no <repositories>: only the mirror can find "a"
        ResolverOptions mirrored = mirrorAll();

        DependencyGraph graph = MavenDependencyResolverTest.resolve(mirrored, app, DEFAULTS);

        assertThat(byKey(graph).get("com.acme:a:1.0").jarFile()).hasValueSatisfying(jar ->
                assertThat(jar.toAbsolutePath().normalize()).startsWith(tmp.resolve("other-local").toAbsolutePath().normalize()));
    }

    @Test
    void plainHttpRepositoryDeclaredInPomIsBlocked() {
        // Maven 3.8.1+ behaviour, always on: external http:* repositories go to a blocked mirror.
        Path app = plainProject("""
                <repositories><repository><id>corp-nexus</id><url>http://repo.example.invalid/maven</url></repository></repositories>
                """ + deps("com.acme:only-on-http:1.0"));

        assertThatThrownBy(() -> MavenDependencyResolverTest.resolve(repo.options(), app, DEFAULTS))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("maven-default-http-blocker")
                .hasMessageContaining("Blocked mirror for repositories: [corp-nexus");
    }

    @Test
    void userMirrorTakesPrecedenceOverTheHttpBlocker() {
        repo.publish("com.acme:a:1.0", "jar", "");
        Path app = plainProject("""
                <repositories><repository><id>corp-nexus</id><url>http://repo.example.invalid/maven</url></repository></repositories>
                """ + deps("com.acme:a:1.0"));
        ResolverOptions options = repo.options(
                "<mirror><id>corp-https</id><mirrorOf>corp-nexus</mirrorOf><url>%s</url></mirror>".formatted(repo.url()), "");

        assertThat(keys(MavenDependencyResolverTest.resolve(options, app, DEFAULTS))).containsOnly("com.acme:a:1.0");
    }

    @Test
    void ignorePomRepositoriesNeverContactsRepositoriesDeclaredInPoms() {
        repo.publish("com.acme:a:1.0", "jar", "");
        repo.publish("com.acme:corp-parent:1", "pom", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));
        ResolverOptions ignoring = repo.options().withIgnorePomRepositories(true);

        // Only the POM-declared "fixture" repository has the artifact.
        assertThat(keys(MavenDependencyResolverTest.resolve(repo.options(), app, DEFAULTS))).containsOnly("com.acme:a:1.0");
        Path freshLocal = tmp.resolve("local-repo");
        deleteRecursively(freshLocal);

        assertThatThrownBy(() -> MavenDependencyResolverTest.resolve(ignoring, app, DEFAULTS))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("com.acme:a:jar:1.0")
                .hasMessageNotContaining("fixture");

        // Parents are not fetched from POM-declared repositories either.
        Path child = repo.project(tmp.resolve("child"), "com.acme:child:1.0",
                "<parent><groupId>com.acme</groupId><artifactId>corp-parent</artifactId><version>1</version><relativePath/></parent>");
        assertThatThrownBy(() -> MavenDependencyResolverTest.resolve(ignoring, child, DEFAULTS))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("com.acme:corp-parent:pom:1");
    }

    @Test
    void ignorePomRepositoriesStillUsesSettingsMirrors() {
        repo.publish("com.acme:a:1.0", "jar", "");
        Path app = plainProject(deps("com.acme:a:1.0"));

        assertThat(keys(MavenDependencyResolverTest.resolve(mirrorAll().withIgnorePomRepositories(true), app, DEFAULTS)))
                .containsOnly("com.acme:a:1.0");
    }

    @Test
    void undecryptablePasswordIsWarnedAboutAndNotUsedButScanContinues() {
        repo.publish("com.acme:a:1.0", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));
        // An encrypted password while settings-security.xml (master password) does not exist.
        ResolverOptions options = repo.options("""
                <servers><server><id>corp</id><username>alice</username><password>{COQLCE6DU6GtcS5P=}</password></server></servers>
                """);

        DependencyGraph graph = MavenDependencyResolverTest.resolve(options, app, DEFAULTS);

        assertThat(keys(graph)).containsOnly("com.acme:a:1.0");
        assertThat(graph.warnings()).anySatisfy(w -> assertThat(w).contains("'corp'").contains("password"));
        assertThat(String.join("\n", graph.warnings())).doesNotContain("COQLCE6DU6GtcS5P").doesNotContain("alice");
    }

    @Test
    void settingsProfilePropertiesAreVisibleToPoms() {
        repo.publish("com.acme:by-default:1.1", "jar", "");
        repo.publish("com.acme:by-id:2.2", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                deps("com.acme:by-default:${default.version}", "com.acme:by-id:${id.version}"));
        ResolverOptions options = repo.options("""
                <profiles>
                  <profile><id>defaults</id><activation><activeByDefault>true</activeByDefault></activation>
                    <properties><default.version>1.1</default.version></properties></profile>
                  <profile><id>corp</id><properties><id.version>2.2</id.version></properties></profile>
                  <profile><id>inactive</id><properties><id.version>9.9</id.version></properties></profile>
                </profiles>
                <activeProfiles><activeProfile>corp</activeProfile></activeProfiles>
                """);

        assertThat(keys(MavenDependencyResolverTest.resolve(options, app, DEFAULTS)))
                .containsOnly("com.acme:by-default:1.1", "com.acme:by-id:2.2");
    }

    @Test
    void sessionHasCliFriendlyTransportDefaultsThatSystemPropertiesOverride() {
        Map<String, Object> config;
        try (MavenRuntime runtime = MavenRuntime.open(repo.options(), false)) {
            config = runtime.session().getConfigProperties();
        }
        assertThat(config).containsEntry("aether.connector.http.retryHandler.count", "2")
                .containsEntry("aether.connector.http.retryHandler.intervalMax", "20000")
                .containsEntry("aether.connector.requestTimeout", "60000")
                .containsEntry("aether.connector.connectTimeout", "10000");
        assertThat((String) config.get("aether.connector.userAgent")).startsWith("Themis/");

        System.setProperty("aether.connector.requestTimeout", "1234");
        try (MavenRuntime runtime = MavenRuntime.open(repo.options(), false)) {
            assertThat(runtime.session().getConfigProperties()).containsEntry("aether.connector.requestTimeout", "1234");
        }
    }

    // ---------------------------------------------------------------------------------------

    private Path plainProject(String extraXml) {
        Path app = tmp.resolve("app");
        FixtureRepo.write(app.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  %s
                </project>""".formatted(extraXml));
        return app;
    }

    /** Settings with {@code mirrorOf *} pointing at the fixture repository and a separate local repo. */
    private ResolverOptions mirrorAll() {
        Path settings = tmp.resolve("mirror-settings.xml");
        FixtureRepo.write(settings, """
                <settings>
                  <localRepository>%s</localRepository>
                  <mirrors><mirror><id>corp</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors>
                </settings>""".formatted(tmp.resolve("other-local"), repo.url()));
        return new ResolverOptions(settings, Optional.empty(), Optional.empty(), tmp.resolve("no-security.xml"), false);
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
