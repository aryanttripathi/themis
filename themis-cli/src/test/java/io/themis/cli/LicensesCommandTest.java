package io.themis.cli;

import io.themis.testing.FixtureRepo;
import io.themis.testing.HttpStub;
import io.themis.testing.Locales;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.themis.testing.FixtureRepo.deps;
import static org.assertj.core.api.Assertions.assertThat;

class LicensesCommandTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;
    private final HttpStub depsDev = HttpStub.start();
    private CliHarness cli;
    private Path app;

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
        repo.publish("com.acme:agpl-lib:1.0", "jar", licenses("GNU Affero General Public License v3"));
        repo.publish("com.acme:mit-lib:1.0", "jar", licenses("The MIT License"));
        repo.publish("com.acme:weak-lib:1.0", "jar", licenses("Eclipse Public License - v 2.0"));
        repo.publish("com.acme:from-depsdev:1.0", "jar", "");      // no <licenses>: deps.dev knows it
        repo.publish("com.acme:mystery:1.0", "jar", "");           // nobody knows
        repo.publish("com.acme:test-gpl:1.0", "jar", licenses("GPLv3"));
        depsDev.depsDev("com.acme:from-depsdev:1.0", "Apache-2.0");
        app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:mit-lib:1.0", "com.acme:agpl-lib:1.0",
                "com.acme:weak-lib:1.0", "com.acme:from-depsdev:1.0", "com.acme:mystery:1.0", "com.acme:test-gpl:1.0:test"));
        cli = new CliHarness(repo, tmp, depsDev.baseUrl());
    }

    @AfterEach
    void stop() {
        depsDev.close();
    }

    @Test
    void printsTableSortedByCategoryWithSummary() {
        int exit = cli.run("licenses", app.toString());

        assertThat(exit).as(cli.err()).isEqualTo(ThemisCli.EXIT_OK);
        List<String> rows = cli.out().lines().filter(l -> l.startsWith("  com.acme:")).toList();
        assertThat(rows).hasSize(5); // test scope excluded by default
        assertThat(rows.get(0)).containsPattern("com\\.acme:agpl-lib:1\\.0\\s+compile\\s+AGPL-3\\.0-only\\s+NETWORK_COPYLEFT\\s+pom");
        assertThat(rows.get(1)).containsPattern("com\\.acme:mystery:1\\.0\\s+compile\\s+-\\s+UNKNOWN\\s+none");
        assertThat(rows.get(2)).containsPattern("com\\.acme:weak-lib:1\\.0\\s+compile\\s+EPL-2\\.0\\s+WEAK_COPYLEFT\\s+pom");
        assertThat(rows.subList(3, 5)).anySatisfy(r -> assertThat(r)
                .containsPattern("com\\.acme:from-depsdev:1\\.0\\s+compile\\s+Apache-2\\.0\\s+PERMISSIVE\\s+deps\\.dev"));
        assertThat(cli.out()).contains("5 dependencies: 1 network copyleft, 0 strong copyleft, 0 proprietary, "
                + "1 unknown, 1 weak copyleft, 2 permissive");
    }

    @Test
    void includeTestShowsTestScope() {
        cli.run("licenses", app.toString(), "--include-test");

        assertThat(cli.out()).containsPattern("com\\.acme:test-gpl:1\\.0\\s+test\\s+GPL-3\\.0-only\\s+STRONG_COPYLEFT");
    }

    @Test
    void offlineNeverContactsDepsDev() {
        int exit = cli.run("licenses", app.toString(), "--offline");

        // Maven offline needs a populated local repository: resolve online once first.
        assertThat(exit).isEqualTo(ThemisCli.EXIT_ERROR);
        int before = depsDev.requests().size();
        cli.run("deps", app.toString());
        cli.out.getBuffer().setLength(0);

        assertThat(cli.run("licenses", app.toString(), "--offline")).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(depsDev.requests()).hasSize(before);
        assertThat(cli.out()).containsPattern("com\\.acme:from-depsdev:1\\.0\\s+compile\\s+-\\s+UNKNOWN");
    }

    @Test
    void projectConfigOverridesALicenseAndExplicitConfigWins() throws Exception {
        Files.writeString(app.resolve(".themis.yml"), "licenses:\n  overrides:\n    \"com.acme:mystery\": \"MIT\"\n");

        cli.run("licenses", app.toString());
        assertThat(cli.out()).containsPattern("com\\.acme:mystery:1\\.0\\s+compile\\s+MIT\\s+PERMISSIVE\\s+config");

        Path other = Files.writeString(tmp.resolve("ci.yml"), "licenses:\n  overrides:\n    \"com.acme:mystery\": \"BSD-3-Clause\"\n");
        cli.out.getBuffer().setLength(0);
        cli.run("licenses", app.toString(), "--config", other.toString());
        assertThat(cli.out()).containsPattern("com\\.acme:mystery:1\\.0\\s+compile\\s+BSD-3-Clause\\s+PERMISSIVE\\s+config");
    }

    @Test
    void unknownConfigKeyIsExit2WithTheKeyNamed() throws Exception {
        Files.writeString(app.resolve(".themis.yml"), "licenses:\n  deny: [NETWORK_COPYLEFT]\n");

        int exit = cli.run("scan", app.toString());

        assertThat(exit).isEqualTo(ThemisCli.EXIT_ERROR);
        assertThat(cli.err()).startsWith("error: ").contains("Unknown key 'licenses.deny'");
    }

    @Test
    void scanReportsLicenseFindingsAndFailsOnTheDefaultThreshold() {
        int exit = cli.run("scan", app.toString());

        assertThat(exit).isEqualTo(ThemisCli.EXIT_FINDINGS); // AGPL is HIGH by default
        assertThat(cli.out()).contains("[HIGH    ] LICENSE       com.acme:agpl-lib:1.0 is AGPL-3.0-only (network copyleft)");
        assertThat(cli.out()).contains("[LOW     ] LICENSE       com.acme:mystery:1.0 has no recognised license (unknown)");
        assertThat(cli.out()).contains("Licenses     : 1 network copyleft, 0 strong copyleft, 0 proprietary, "
                + "1 unknown, 1 weak copyleft, 2 permissive");
    }

    @Test
    void cliFailOnBeatsConfigFileWhichBeatsDefault() throws Exception {
        Files.writeString(app.resolve(".themis.yml"), "failOn:\n  severity: CRITICAL\n");
        assertThat(cli.run("scan", app.toString())).isEqualTo(ThemisCli.EXIT_OK);          // file: CRITICAL
        assertThat(cli.run("scan", app.toString(), "--fail-on", "HIGH")).isEqualTo(ThemisCli.EXIT_FINDINGS); // CLI wins
        assertThat(Locales.with(Locales.TURKISH, () -> cli.run("scan", app.toString(), "--fail-on", "low")))
                .isEqualTo(ThemisCli.EXIT_FINDINGS);
    }

    @Test
    void policyFromConfigChangesSeverities() throws Exception {
        Files.writeString(app.resolve(".themis.yml"), """
                licenses:
                  severity: {NETWORK_COPYLEFT: NONE}
                  allowArtifacts: ["com.acme:mystery"]
                """);

        int exit = cli.run("scan", app.toString());

        assertThat(exit).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(cli.out()).doesNotContain("agpl-lib:1.0 is").doesNotContain("mystery:1.0 has");
    }

    @Test
    void jsonReportCarriesLicensePerDependency() throws Exception {
        Path out = tmp.resolve("report");
        cli.run("scan", app.toString(), "--format", "json", "--output", out.toString());

        String json = Files.readString(out.resolve("report.json"));
        assertThat(json).containsPattern("\"coordinate\" : \"com.acme:agpl-lib:1.0\"[^}]*\"license\" : \\{\\s*"
                + "\"spdx\" : \"AGPL-3.0-only\",\\s*\"category\" : \"NETWORK_COPYLEFT\",\\s*\"source\" : \"pom\"");
    }

    private static String licenses(String name) {
        return "<licenses><license><name>" + name + "</name></license></licenses>";
    }
}
