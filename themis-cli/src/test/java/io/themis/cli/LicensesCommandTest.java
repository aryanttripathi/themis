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
        repo.publish("org.thirdparty:from-depsdev:1.0", "jar", "");      // no <licenses>: deps.dev knows it
        repo.publish("com.acme:mystery:1.0", "jar", "");           // nobody knows
        repo.publish("com.acme:test-gpl:1.0", "jar", licenses("GPLv3"));
        depsDev.depsDev("org.thirdparty:from-depsdev:1.0", "Apache-2.0");
        depsDev.on("/v1/querybatch", new HttpStub.Response(200, "{\"results\":[]}"));   // OSV: no known vulnerabilities
        app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:mit-lib:1.0", "com.acme:agpl-lib:1.0",
                "com.acme:weak-lib:1.0", "org.thirdparty:from-depsdev:1.0", "com.acme:mystery:1.0", "com.acme:test-gpl:1.0:test"));
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
        List<String> rows = cli.out().lines().filter(l -> l.startsWith("  com.acme:") || l.startsWith("  org.thirdparty:")).toList();
        assertThat(rows).hasSize(5); // test scope excluded by default
        assertThat(rows.get(0)).containsPattern("com\\.acme:agpl-lib:1\\.0\\s+compile\\s+AGPL-3\\.0-only\\s+NETWORK_COPYLEFT\\s+pom");
        assertThat(rows.get(1)).containsPattern("com\\.acme:mystery:1\\.0\\s+compile\\s+-\\s+UNKNOWN\\s+none");
        assertThat(rows.get(2)).containsPattern("com\\.acme:weak-lib:1\\.0\\s+compile\\s+EPL-2\\.0\\s+WEAK_COPYLEFT\\s+pom");
        assertThat(rows.subList(3, 5)).anySatisfy(r -> assertThat(r)
                .containsPattern("org\\.thirdparty:from-depsdev:1\\.0\\s+compile\\s+Apache-2\\.0\\s+PERMISSIVE\\s+deps\\.dev"));
        assertThat(cli.out()).contains("5 dependencies: 1 network copyleft, 0 strong copyleft, 0 proprietary, "
                + "1 unknown, 1 weak copyleft, 2 permissive");
    }

    @Test
    void internalCoordinatesNeverReachPublicApis() {
        cli.run("scan", app.toString());

        assertThat(depsDev.everythingSent()).contains("from-depsdev").doesNotContain("com.acme").doesNotContain("com%3Aacme")
                .doesNotContain(tmp.toString());
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
        assertThat(cli.out()).containsPattern("org\\.thirdparty:from-depsdev:1\\.0\\s+compile\\s+-\\s+UNKNOWN");
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

    @Test
    void lenientScanWarnsAboutUnresolvableDependenciesAndScansTheRest() {
        Path broken = repo.project(tmp.resolve("broken"), "com.acme:broken:1.0", deps("com.acme:mit-lib:1.0", "com.acme:gone:1.0"));

        assertThat(cli.run("scan", broken.toString())).isEqualTo(ThemisCli.EXIT_ERROR);
        int exit = cli.run("scan", broken.toString(), "--lenient");

        assertThat(exit).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(cli.out()).contains("Dependencies : 2"); // the missing one is still known by coordinate (no jar)
        assertThat(cli.err()).contains("warning: ").contains("com.acme:gone:jar:1.0");
    }

    @Test
    void wildcardOverrideCoversAWholeGroup() throws Exception {
        Files.writeString(app.resolve(".themis.yml"), "licenses:\n  overrides:\n    \"com.acme:my*\": \"LicenseRef-Acme\"\n");

        cli.run("licenses", app.toString());

        assertThat(cli.out()).containsPattern("com\\.acme:mystery:1\\.0\\s+compile\\s+LicenseRef-Acme\\s+PROPRIETARY\\s+config");
    }

    @Test
    void manyFindingsGoToAnHtmlReportInsteadOfTheConsole() throws Exception {
        Path out = tmp.resolve("rep");
        int exit = cli.run("scan", app.toString(), "--console-limit", "2", "--output", out.toString());

        assertThat(exit).isEqualTo(ThemisCli.EXIT_FINDINGS);
        assertThat(cli.out()).contains("Findings     : ").contains("exceeds the console limit of 2")
                .contains("HTML report: " + out.resolve("report.html")).doesNotContain("[HIGH    ]");
        assertThat(Files.readString(out.resolve("report.html"))).contains("com.acme:agpl-lib:1.0");
    }

    @Test
    void consoleLimitComesFromConfigAndCliWins() throws Exception {
        Files.writeString(app.resolve(".themis.yml"), "report:\n  consoleLimit: 1\n  open: false\n");
        Path out = tmp.resolve("rep2");

        cli.run("scan", app.toString(), "--output", out.toString());
        assertThat(cli.out()).contains("HTML report:");

        cli.out.getBuffer().setLength(0);
        cli.run("scan", app.toString(), "--output", out.toString(), "--console-limit", "100");
        assertThat(cli.out()).contains("[HIGH    ]").doesNotContain("HTML report:");
    }

    @Test
    void htmlFormatCanBeRequestedExplicitly() throws Exception {
        Path out = tmp.resolve("rep3");
        cli.run("scan", app.toString(), "--format", "console,html", "--output", out.toString(), "--no-open");

        assertThat(out.resolve("report.html")).exists();
        assertThat(cli.out()).contains("[HIGH    ]").contains("HTML report:");
    }

    @Test
    void baselineAcceptsTodaysFindingsSoOnlyNewOnesFailTheBuild() throws Exception {
        assertThat(cli.run("scan", app.toString())).isEqualTo(ThemisCli.EXIT_FINDINGS);

        assertThat(cli.run("baseline", app.toString())).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(app.resolve(".themis-baseline.json")).exists();
        assertThat(cli.out()).contains("Baseline written");

        cli.out.getBuffer().setLength(0);
        assertThat(cli.run("scan", app.toString())).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(cli.out()).containsPattern("Suppressed\\s+: \\d+ \\(\\d+ baseline, 0 suppress\\)").doesNotContain("[HIGH    ]");

        assertThat(cli.run("scan", app.toString(), "--no-baseline")).isEqualTo(ThemisCli.EXIT_FINDINGS);
    }

    @Test
    void suppressEntriesInConfigHideFindingsWithAReason() throws Exception {
        Files.writeString(app.resolve(".themis.yml"), """
                suppress:
                  - id: "LICENSE:NETWORK_COPYLEFT:com.acme:agpl-lib"
                    reason: "internal tool, never offered over a network"
                """);

        int exit = cli.run("scan", app.toString());

        assertThat(exit).isEqualTo(ThemisCli.EXIT_OK);
        assertThat(cli.out()).contains("Suppressed   : 1 (0 baseline, 1 suppress)").doesNotContain("agpl-lib:1.0 is");
    }

    @Test
    void sarifFormatWritesACodeScanningFile() throws Exception {
        Path out = tmp.resolve("sarif");
        cli.run("scan", app.toString(), "--format", "sarif", "--output", out.toString());

        assertThat(Files.readString(out.resolve("themis.sarif"))).contains("\"version\" : \"2.1.0\"").contains("license/network-copyleft");
        assertThat(cli.out()).contains("SARIF report: ");
    }

    private static String licenses(String name) {
        return "<licenses><license><name>" + name + "</name></license></licenses>";
    }
}
