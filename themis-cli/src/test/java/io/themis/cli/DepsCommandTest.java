package io.themis.cli;

import io.themis.resolve.maven.ResolverOptions;
import io.themis.testing.FixtureRepo;
import io.themis.testing.Locales;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.themis.testing.FixtureRepo.deps;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI tests run against {@link ThemisCli#newCommandLine(ResolverOptions)} with fixture options only,
 * so -Dmaven.repo.local, MAVEN_HOME or the real ~/.m2/settings.xml can never leak in.
 */
class DepsCommandTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;
    private ResolverOptions options;
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
        options = repo.options();
        repo.publish("com.acme:b:1.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:b:1.0"));
        repo.publish("com.acme:t:1.0", "jar", "");
        repo.publish("com.acme:p:1.0", "jar", "");
    }

    @AfterEach
    void clearProperty() {
        System.clearProperty("maven.repo.local");
    }

    @Test
    void printsDirectAndTransitiveDependenciesWithScopeAndVia() {
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                deps("com.acme:a:1.0", "com.acme:t:1.0:test", "com.acme:p:1.0:provided"));

        int exit = run("deps", app.toString());

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        String text = out.toString();
        assertThat(text).contains("com.acme:app:1.0");
        assertThat(text).contains("3 dependencies (2 direct)");
        assertThat(text).containsPattern("com\\.acme:a:1\\.0\\s+compile\\s+direct\\R");
        assertThat(text).containsPattern("com\\.acme:b:1\\.0\\s+compile\\s+transitive\\s+via com\\.acme:a:1\\.0");
        assertThat(text).containsPattern("com\\.acme:p:1\\.0\\s+provided\\s+direct");
        assertThat(text).doesNotContain("com.acme:t:1.0");
    }

    @Test
    void scopeNamesAreLowerCaseUnderTurkishLocale() {
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        int exit = Locales.with(Locales.TURKISH, () -> run("deps", app.toString()));

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(out.toString()).contains(" compile ").doesNotContain("compıle");
    }

    @Test
    void includeTestAddsTestScope() {
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0", "com.acme:t:1.0:test"));

        int exit = run("deps", app.toString(), "--include-test");

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(out.toString()).containsPattern("com\\.acme:t:1\\.0\\s+test\\s+direct");
    }

    @Test
    void multiModuleShowsDeclaringModules() {
        Path root = repo.project(tmp.resolve("multi"), "com.acme:multi:1.0", "pom",
                "<modules><module>core</module><module>web</module></modules>");
        for (String m : new String[]{"core", "web"}) {
            FixtureRepo.write(root.resolve(m + "/pom.xml"), """
                    <project><modelVersion>4.0.0</modelVersion>
                      <parent><groupId>com.acme</groupId><artifactId>multi</artifactId><version>1.0</version></parent>
                      <artifactId>%s</artifactId>
                      %s
                    </project>""".formatted(m, deps("com.acme:a:1.0")));
        }

        int exit = run("deps", root.toString());

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(out.toString()).containsPattern(
                "com\\.acme:a:1\\.0\\s+compile\\s+direct\\s+in com\\.acme:core:1\\.0, com\\.acme:web:1\\.0\\R");
        assertThat(out.toString()).containsPattern(
                "com\\.acme:b:1\\.0\\s+compile\\s+transitive\\s+in com\\.acme:core:1\\.0, com\\.acme:web:1\\.0 via com\\.acme:a:1\\.0");
    }

    @Test
    void warningsGoToStderr() {
        repo.publishJarWithoutPom("com.acme:nopom:1.0");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:nopom:1.0"));

        int exit = run("deps", app.toString());

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(err.toString()).contains(
                "warning: The POM for com.acme:nopom:jar:1.0 is missing, no dependency information available");
        assertThat(out.toString()).doesNotContain("warning");
    }

    @Test
    void ignorePomRepositoriesFlagSkipsPomDeclaredRepositories() {
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        int exit = run("deps", app.toString(), "--ignore-pom-repositories");

        assertThat(exit).isEqualTo(ThemisCli.EXIT_ERROR);
        assertThat(err.toString()).startsWith("error: ").contains("com.acme:a:jar:1.0");
    }

    @Test
    void settingsFlagReplacesOnlyTheUserSettingsOfTheBaseOptions() {
        repo.publish("com.acme:c:1.0", "jar", "");
        Path app = tmp.resolve("app");
        FixtureRepo.write(app.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>app</artifactId><version>1.0</version>
                  %s
                </project>""".formatted(deps("com.acme:c:1.0")));
        Path other = tmp.resolve("other-settings.xml");
        FixtureRepo.write(other, """
                <settings><localRepository>%s</localRepository>
                  <mirrors><mirror><id>m</id><mirrorOf>*</mirrorOf><url>%s</url></mirror></mirrors></settings>
                """.formatted(tmp.resolve("other-local"), repo.url()));

        int exit = run("deps", app.toString(), "--settings", other.toString());

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(tmp.resolve("other-local/com/acme/c/1.0/c-1.0.jar")).exists();
    }

    @Test
    void mavenRepoLocalSystemPropertyDoesNotLeakIntoTests() {
        Path elsewhere = tmp.resolve("should-stay-empty");
        System.setProperty("maven.repo.local", elsewhere.toString());
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        int exit = run("deps", app.toString(), "--settings", options.userSettings().toString());

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(Files.exists(elsewhere)).isFalse();
    }

    @Test
    void offlineWithEmptyLocalRepositoryFailsWithExit2() {
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        int exit = run("deps", app.toString(), "--offline");

        assertThat(exit).isEqualTo(ThemisCli.EXIT_ERROR);
        assertThat(err.toString()).startsWith("error: ").contains("com.acme:a");
    }

    @Test
    void unresolvableDependencyPrintsErrorAndExits2() {
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:nope:1.0"));

        int exit = run("deps", app.toString());

        assertThat(exit).isEqualTo(ThemisCli.EXIT_ERROR);
        assertThat(err.toString()).startsWith("error: ").contains("com.acme:nope:jar:1.0");
    }

    @Test
    void scanStillWorksCountsDependenciesAndPrintsWarnings() {
        repo.publishJarWithoutPom("com.acme:nopom:1.0");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0", "com.acme:nopom:1.0"));

        int exit = run("scan", app.toString());

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(out.toString()).contains("Dependencies : 3 (2 direct)");
        assertThat(err.toString()).contains("warning: The POM for com.acme:nopom:jar:1.0 is missing");
    }

    private int run(String... args) {
        CommandLine cli = ThemisCli.newCommandLine(options);
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }
}
