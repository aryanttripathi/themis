package io.themis.resolve.gradle;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;
import io.themis.model.Scope;
import io.themis.resolve.DeclaredLicense;
import io.themis.resolve.PomLicenses;
import io.themis.resolve.ProjectSession;
import io.themis.resolve.ResolutionException;
import io.themis.testing.FixtureRepo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static io.themis.testing.FixtureRepo.deps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs a real Gradle (the local installation found by {@link TestGradle}) against fixture builds whose
 * only repository is an on-disk Maven repo, so the tests are hermetic. Skipped when no Gradle is installed.
 */
class GradleDependencyResolverTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;
    private GradleDependencyResolver resolver;

    @BeforeAll
    static void requireGradle() {
        assumeTrue(TestGradle.command().isPresent(), "no Gradle installation found; set THEMIS_TEST_GRADLE");
    }

    @AfterAll
    static void stopDaemons() {
        TestGradle.stopDaemons();
    }

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
        resolver = new GradleDependencyResolver(new GradleOptions(TestGradle.command(), Optional.of(TestGradle.userHome()),
                Duration.ofMinutes(3)));
    }

    @Test
    void supportsGradleBuildsButNotMavenOnes() throws Exception {
        Path gradle = Files.createDirectories(tmp.resolve("g"));
        Files.writeString(gradle.resolve("build.gradle"), "");
        Path kts = Files.createDirectories(tmp.resolve("k"));
        Files.writeString(kts.resolve("settings.gradle.kts"), "");
        Path maven = Files.createDirectories(tmp.resolve("m"));
        Files.writeString(maven.resolve("pom.xml"), "<project/>");

        assertThat(resolver.supports(gradle)).isTrue();
        assertThat(resolver.supports(kts)).isTrue();
        assertThat(resolver.supports(maven)).isFalse();
    }

    @Test
    void mapsGradleConfigurationsToScopesWithPathsAndJars() {
        repo.publish("com.acme:b:1.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:b:1.0"));
        repo.publish("com.acme:r:1.0", "jar", "");
        repo.publish("com.acme:p:1.0", "jar", "");
        repo.publish("com.acme:t:1.0", "jar", "");
        Path dir = build("app", """
                dependencies {
                    implementation 'com.acme:a:1.0'
                    runtimeOnly 'com.acme:r:1.0'
                    compileOnly 'com.acme:p:1.0'
                    testImplementation 'com.acme:t:1.0'
                }
                """);

        DependencyGraph graph = resolve(dir, ScanConfig.defaults());

        Coordinate root = new Coordinate("com.acme", "app", "1.0");
        assertThat(graph.root()).isEqualTo(root);
        assertThat(dep(graph, "com.acme:a:1.0")).satisfies(d -> {
            assertThat(d.scope()).isEqualTo(Scope.COMPILE);
            assertThat(d.direct()).isTrue();
            assertThat(d.jarFile()).hasValueSatisfying(f -> assertThat(f).exists().hasFileName("a-1.0.jar"));
        });
        assertThat(dep(graph, "com.acme:b:1.0")).satisfies(d -> {
            assertThat(d.scope()).isEqualTo(Scope.COMPILE);
            assertThat(d.direct()).isFalse();
            assertThat(d.path()).containsExactly(root, Coordinate.parse("com.acme:a:1.0"), Coordinate.parse("com.acme:b:1.0"));
        });
        assertThat(dep(graph, "com.acme:r:1.0").scope()).isEqualTo(Scope.RUNTIME);
        assertThat(dep(graph, "com.acme:p:1.0").scope()).isEqualTo(Scope.PROVIDED);
        assertThat(coordinates(graph)).doesNotContain("com.acme:t:1.0");

        DependencyGraph withTests = resolve(dir, ScanConfig.defaults().withIncludeTestScope(true));
        assertThat(dep(withTests, "com.acme:t:1.0").scope()).isEqualTo(Scope.TEST);
    }

    @Test
    void usesGradlesConflictResolutionHighestVersionWins() {
        repo.publish("com.acme:c:1.0", "jar", "");
        repo.publish("com.acme:c:2.0", "jar", "");
        repo.publish("com.acme:x:1.0", "jar", deps("com.acme:c:1.0"));
        repo.publish("com.acme:y:1.0", "jar", deps("com.acme:c:2.0"));
        Path dir = build("app", "dependencies { implementation 'com.acme:x:1.0'\n implementation 'com.acme:y:1.0' }\n");

        DependencyGraph graph = resolve(dir, ScanConfig.defaults());

        assertThat(coordinates(graph)).contains("com.acme:c:2.0").doesNotContain("com.acme:c:1.0");
    }

    @Test
    void platformsAndBomsAreNotReportedAsDependencies() {
        repo.publish("com.acme:c:1.5", "jar", "");
        repo.publish("com.acme:bom:1.0", "pom", "<dependencyManagement>" + deps("com.acme:c:1.5") + "</dependencyManagement>");
        Path dir = build("app", "dependencies { implementation platform('com.acme:bom:1.0')\n implementation 'com.acme:c' }\n");

        DependencyGraph graph = resolve(dir, ScanConfig.defaults());

        assertThat(coordinates(graph)).containsExactly("com.acme:c:1.5");
    }

    @Test
    void multiProjectBuildMergesSubprojectsWithoutListingSiblingsAsDependencies() throws Exception {
        repo.publish("com.acme:lang:1.0", "jar", "");
        repo.publish("com.acme:web:1.0", "jar", "");
        Path dir = Files.createDirectories(tmp.resolve("multi"));
        Files.createDirectories(dir.resolve("core"));
        Files.createDirectories(dir.resolve("app"));
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'multi'\ninclude 'core', 'app'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                allprojects { group = 'com.acme'; version = '2.0' }
                subprojects {
                    apply plugin: 'java'
                    java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
                    repositories { maven { url = uri('%s') } }
                }
                project(':core') { dependencies { implementation 'com.acme:lang:1.0' } }
                project(':app') { dependencies { implementation project(':core'); implementation 'com.acme:web:1.0' } }
                """.formatted(repo.url()));

        try (ProjectSession session = resolver.open(dir, ScanConfig.defaults())) {
            DependencyGraph graph = session.resolveDependencies();
            ProjectInfo info = session.project();

            assertThat(info.coordinate()).isEqualTo(new Coordinate("com.acme", "multi", "2.0"));
            assertThat(info.javaRelease()).isEqualTo(11);
            assertThat(coordinates(graph)).containsExactlyInAnyOrder("com.acme:lang:1.0", "com.acme:web:1.0");
            Dependency lang = dep(graph, "com.acme:lang:1.0");
            assertThat(lang.modules()).contains(new Coordinate("com.acme", "core", "2.0"));
            assertThat(lang.scope()).isEqualTo(Scope.COMPILE);
        }
    }

    @Test
    void rootProjectWithoutRepositoriesIsSkippedWithAWarning() throws Exception {
        // common BFF shape: dependency management on the root, repositories only in subprojects
        repo.publish("com.acme:c:1.5", "jar", "");
        repo.publish("com.acme:bom:1.0", "pom", "<dependencyManagement>" + deps("com.acme:c:1.5") + "</dependencyManagement>");
        Path dir = Files.createDirectories(tmp.resolve("bff"));
        Files.createDirectories(dir.resolve("svc"));
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'bff'\ninclude 'svc'\n");
        Files.writeString(dir.resolve("build.gradle"), """
                allprojects {
                    group = 'com.acme'; version = '1.0'
                    apply plugin: 'java'
                    dependencies { implementation platform('com.acme:bom:1.0') }
                }
                project(':svc') {
                    repositories { maven { url = uri('%s') } }
                    dependencies { implementation 'com.acme:c' }
                }
                """.formatted(repo.url()));

        DependencyGraph graph = resolve(dir, ScanConfig.defaults());

        assertThat(coordinates(graph)).containsExactly("com.acme:c:1.5");
        assertThat(graph.warnings()).anySatisfy(w -> assertThat(w).contains("project ':'").contains("no repositories"));
    }

    @Test
    void readsJavaReleaseAndProjectCoordinate() throws Exception {
        Path dir = build("svc", "java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }\n");
        Files.createDirectories(dir.resolve("src/main/java"));

        try (ProjectSession session = resolver.open(dir, ScanConfig.defaults())) {
            assertThat(session.project().javaRelease()).isEqualTo(17);
            assertThat(session.project().coordinate()).isEqualTo(new Coordinate("com.acme", "svc", "1.0"));
            assertThat(session.project().sourceRoots()).contains(dir.toRealPath().resolve("src/main/java"));
        }
    }

    @Test
    void exposesPomLicensesIncludingInheritedFromParent() {
        repo.publish("com.acme:parent:1", "pom", "<licenses><license><name>The MIT License</name></license></licenses>");
        repo.publish("com.acme:own:1.0", "jar",
                "<licenses><license><name>Apache License, Version 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0</url></license></licenses>");
        repo.publish("com.acme:child:1.0", "jar",
                "<parent><groupId>com.acme</groupId><artifactId>parent</artifactId><version>1</version></parent>");
        Path dir = build("app", "dependencies { implementation 'com.acme:own:1.0'\n implementation 'com.acme:child:1.0' }\n");

        try (ProjectSession session = resolver.open(dir, ScanConfig.defaults())) {
            session.resolveDependencies();
            PomLicenses own = session.pomLicenses(Coordinate.parse("com.acme:own:1.0"));
            PomLicenses child = session.pomLicenses(Coordinate.parse("com.acme:child:1.0"));

            assertThat(own.licenses()).containsExactly(
                    new DeclaredLicense("Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0"));
            assertThat(own.inherited()).isFalse();
            assertThat(child.licenses()).extracting(DeclaredLicense::name).containsExactly("The MIT License");
            assertThat(child.inherited()).isTrue();
        }
    }

    @Test
    void unresolvableDependencyIsAClearResolutionException() {
        Path dir = build("app", "dependencies { implementation 'com.acme:missing:9.9' }\n");

        assertThatThrownBy(() -> resolve(dir, ScanConfig.defaults()))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("com.acme:missing:9.9")
                .satisfies(e -> assertThat(((ResolutionException) e).unresolved()).anyMatch(u -> u.contains("com.acme:missing:9.9")));
    }

    @Test
    void lenientModeReportsUnresolvableDependenciesAsWarnings() {
        repo.publish("com.acme:ok:1.0", "jar", "");
        Path dir = build("app", "dependencies { implementation 'com.acme:ok:1.0'\n implementation 'com.acme:missing:9.9' }\n");

        DependencyGraph graph = resolve(dir, ScanConfig.defaults().withLenient(true));

        assertThat(coordinates(graph)).containsExactly("com.acme:ok:1.0");
        assertThat(graph.warnings()).anySatisfy(w -> assertThat(w).contains("com.acme:missing:9.9"));
    }

    @Test
    void brokenBuildScriptFailsWithGradlesOutput() {
        Path dir = build("app", "this is not groovy {{{\n");

        assertThatThrownBy(() -> resolve(dir, ScanConfig.defaults()))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("Gradle failed");
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Single-project build {@code com.acme:<name>:1.0} with the fixture repository. */
    private Path build(String name, String extra) {
        try {
            Path dir = Files.createDirectories(tmp.resolve(name));
            Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = '" + name + "'\n");
            Files.writeString(dir.resolve("build.gradle"), """
                    plugins { id 'java' }
                    group = 'com.acme'
                    version = '1.0'
                    repositories { maven { url = uri('%s') } }
                    %s
                    """.formatted(repo.url(), extra));
            return dir;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private DependencyGraph resolve(Path dir, ScanConfig config) {
        try (ProjectSession session = resolver.open(dir, config)) {
            return session.resolveDependencies();
        }
    }

    private static Dependency dep(DependencyGraph graph, String gav) {
        return graph.dependencies().stream().filter(d -> d.coordinate().toString().equals(gav)).findFirst()
                .orElseThrow(() -> new AssertionError(gav + " not in " + coordinates(graph)));
    }

    private static List<String> coordinates(DependencyGraph graph) {
        return graph.dependencies().stream().map(d -> d.coordinate().toString()).toList();
    }
}
