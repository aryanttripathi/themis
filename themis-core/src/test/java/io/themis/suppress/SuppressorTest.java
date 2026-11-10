package io.themis.suppress;

import io.themis.model.Coordinate;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.ProjectInfo;
import io.themis.model.ScanReport;
import io.themis.model.Severity;
import io.themis.model.SuppressedFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SuppressorTest {

    @TempDir
    Path tmp;

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    private static final Finding MYSQL_VULN = finding("VULNERABILITY:GHSA-aaaa:com.mysql:mysql-connector-j", Severity.HIGH);
    private static final Finding JSON_LICENSE = finding("LICENSE:proprietary:org.json:json", Severity.MEDIUM);
    private static final Finding SECRET = finding("SECRET:aws-access-key:src/main/resources/a.yml:abc123def456", Severity.CRITICAL);

    @Test
    void configSuppressionsMatchIdsAndWildcardsAndKeepTheReason() {
        List<Suppression> rules = List.of(
                new Suppression("VULNERABILITY:GHSA-*:com.mysql:*", "driver only used by batch job", Optional.empty()),
                new Suppression("LICENSE:proprietary:org.json:json", "legal approved 2026-10", Optional.of(TODAY)));

        ScanReport out = Suppressor.apply(report(MYSQL_VULN, JSON_LICENSE, SECRET), rules, Baseline.EMPTY, TODAY);

        assertThat(out.findings()).containsExactly(SECRET);
        assertThat(out.suppressed()).extracting(SuppressedFinding::reason)
                .containsExactly("suppressed: driver only used by batch job", "suppressed: legal approved 2026-10");
        assertThat(out.hasFindingsAtLeast(Severity.HIGH)).isTrue();   // the secret still counts
    }

    @Test
    void expiredSuppressionsStopApplyingAndWarn() {
        List<Suppression> rules = List.of(new Suppression("LICENSE:*", "temporary", Optional.of(TODAY.minusDays(1))));

        ScanReport out = Suppressor.apply(report(JSON_LICENSE), rules, Baseline.EMPTY, TODAY);

        assertThat(out.findings()).containsExactly(JSON_LICENSE);
        assertThat(out.graph().warnings()).anySatisfy(w -> assertThat(w).contains("expired on 2026-11-09").contains("LICENSE:*"));
    }

    @Test
    void baselineHidesKnownFindingsOnly() throws Exception {
        Path file = tmp.resolve(".themis-baseline.json");
        Baseline.write(file, report(MYSQL_VULN, JSON_LICENSE));
        assertThat(Files.readString(file)).contains("\"schemaVersion\"").contains("GHSA-aaaa").doesNotContain("abc123");

        Baseline baseline = Baseline.read(file);
        ScanReport out = Suppressor.apply(report(MYSQL_VULN, JSON_LICENSE, SECRET), List.of(), baseline, TODAY);

        assertThat(baseline.ids()).containsExactlyInAnyOrder(MYSQL_VULN.id(), JSON_LICENSE.id());
        assertThat(out.findings()).containsExactly(SECRET);
        assertThat(out.suppressed()).allMatch(s -> s.reason().equals("baseline"));
    }

    @Test
    void unusedSuppressionsAreReportedSoTheyCanBeCleanedUp() {
        List<Suppression> rules = List.of(new Suppression("VULNERABILITY:GHSA-gone:*", "fixed long ago", Optional.empty()));

        ScanReport out = Suppressor.apply(report(SECRET), rules, Baseline.EMPTY, TODAY);

        assertThat(out.graph().warnings()).anySatisfy(w -> assertThat(w).contains("matched nothing").contains("GHSA-gone"));
    }

    @Test
    void emptyBaselineFromMissingFile() {
        assertThat(Baseline.readIfExists(tmp.resolve("nope.json")).ids()).isEmpty();
        assertThat(Set.copyOf(Baseline.EMPTY.ids())).isEmpty();
    }

    private static ScanReport report(Finding... findings) {
        ProjectInfo p = new ProjectInfo(Path.of("/p"), Coordinate.parse("com.acme:app:1"), 17, List.of(), List.of());
        return new ScanReport("t", Instant.EPOCH, Duration.ZERO, p, DependencyGraph.empty(p.coordinate()), List.of(findings), List.of(), List.of());
    }

    private static Finding finding(String id, Severity s) {
        return new Finding(id, FindingType.valueOf(id.substring(0, id.indexOf(':'))), s, id, "", Optional.empty(), Optional.empty(), Map.of());
    }
}
