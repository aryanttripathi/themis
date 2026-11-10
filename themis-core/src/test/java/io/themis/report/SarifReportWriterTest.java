package io.themis.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.Location;
import io.themis.model.ProjectInfo;
import io.themis.model.ScanReport;
import io.themis.model.Scope;
import io.themis.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SarifReportWriterTest {

    @TempDir
    Path root;

    private static final Coordinate APP = Coordinate.parse("com.acme:app:1.0");
    private static final Coordinate STARTER = Coordinate.parse("org.vendor:starter:3.0");
    private static final Coordinate LIB = Coordinate.parse("org.vendor:lib:1.0");

    @Test
    void writesSarif210WithRulesLevelsLocationsAndFingerprints() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.vendor</groupId>
                      <artifactId>starter</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Finding vuln = new Finding("VULNERABILITY:GHSA-abcd:org.vendor:lib", FindingType.VULNERABILITY, Severity.CRITICAL,
                "org.vendor:lib:1.0 has GHSA-abcd (CVE-2024-1): RCE", "Fixed in 1.5.", Optional.of(LIB), Optional.empty(),
                Map.of("cvss", "9.8", "url", "https://osv.dev/vulnerability/GHSA-abcd"));
        Finding secret = new Finding("SECRET:aws-access-key:src/main/resources/a.yml:abc", FindingType.SECRET, Severity.HIGH,
                "AWS access key in src/main/resources/a.yml:7", "Rotate it.", Optional.empty(),
                Optional.of(new Location(Path.of("src/main/resources/a.yml"), 7, 15)), Map.of("rule", "aws-access-key", "masked", "AKIA****LE"));
        Finding license = new Finding("LICENSE:network-copyleft:org.vendor:starter", FindingType.LICENSE, Severity.LOW,
                "org.vendor:starter:3.0 is AGPL-3.0-only", "", Optional.of(STARTER), Optional.empty(), Map.of());

        JsonNode sarif = new ObjectMapper().readTree(Files.readString(new SarifReportWriter().write(report(vuln, secret, license), root.resolve("out"))));

        assertThat(sarif.path("version").asText()).isEqualTo("2.1.0");
        assertThat(sarif.path("$schema").asText()).contains("sarif");
        JsonNode run = sarif.path("runs").get(0);
        assertThat(run.at("/tool/driver/name").asText()).isEqualTo("Themis");
        JsonNode results = run.path("results");
        assertThat(results).hasSize(3);

        JsonNode v = results.get(0);
        assertThat(v.path("ruleId").asText()).isEqualTo("GHSA-abcd");
        assertThat(v.path("level").asText()).isEqualTo("error");
        // transitive dependency: anchored at the direct dependency's line in the build file
        assertThat(v.at("/locations/0/physicalLocation/artifactLocation/uri").asText()).isEqualTo("pom.xml");
        assertThat(v.at("/locations/0/physicalLocation/region/startLine").asInt()).isEqualTo(5);
        assertThat(v.path("partialFingerprints").path("themisFindingId/v1").asText()).hasSize(32);

        JsonNode s = results.get(1);
        assertThat(s.path("ruleId").asText()).isEqualTo("secret/aws-access-key");
        assertThat(s.at("/locations/0/physicalLocation/artifactLocation/uri").asText()).isEqualTo("src/main/resources/a.yml");
        assertThat(s.at("/locations/0/physicalLocation/region/startLine").asInt()).isEqualTo(7);
        assertThat(s.at("/locations/0/physicalLocation/region/startColumn").asInt()).isEqualTo(15);

        assertThat(results.get(2).path("level").asText()).isEqualTo("note");

        JsonNode rules = run.at("/tool/driver/rules");
        assertThat(rules.findValuesAsText("id")).contains("GHSA-abcd", "secret/aws-access-key", "license/network-copyleft");
        JsonNode ghsa = rules.get(0);
        assertThat(ghsa.at("/properties/security-severity").asText()).isEqualTo("9.8");
        assertThat(ghsa.at("/helpUri").asText()).isEqualTo("https://osv.dev/vulnerability/GHSA-abcd");
    }

    @Test
    void gradleBuildFilesAreUsedWhenThereIsNoPom() throws Exception {
        Files.writeString(root.resolve("build.gradle"), "dependencies {\n    implementation 'org.vendor:lib:1.0'\n}\n");
        Finding direct = new Finding("LICENSE:unknown:org.vendor:lib", FindingType.LICENSE, Severity.LOW, "unknown", "",
                Optional.of(LIB), Optional.empty(), Map.of());
        Dependency dep = new Dependency(LIB, Scope.COMPILE, true, List.of(APP, LIB), Optional.empty());

        JsonNode sarif = new ObjectMapper().readTree(Files.readString(new SarifReportWriter().write(
                report(List.of(dep), direct), root.resolve("out"))));

        JsonNode loc = sarif.at("/runs/0/results/0/locations/0/physicalLocation");
        assertThat(loc.at("/artifactLocation/uri").asText()).isEqualTo("build.gradle");
        assertThat(loc.at("/region/startLine").asInt()).isEqualTo(2);
    }

    private ScanReport report(Finding... findings) {
        Dependency dep = new Dependency(LIB, Scope.COMPILE, false, List.of(APP, STARTER, LIB), Optional.empty());
        Dependency starter = new Dependency(STARTER, Scope.COMPILE, true, List.of(APP, STARTER), Optional.empty());
        return report(List.of(starter, dep), findings);
    }

    private ScanReport report(List<Dependency> deps, Finding... findings) {
        ProjectInfo p = new ProjectInfo(root, APP, 17, List.of(), List.of());
        return new ScanReport("t", Instant.EPOCH, Duration.ZERO, p, new DependencyGraph(APP, deps), List.of(findings), List.of(), List.of());
    }
}
