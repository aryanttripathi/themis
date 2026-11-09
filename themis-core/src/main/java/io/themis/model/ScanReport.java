package io.themis.model;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The complete result of a scan; every reporter renders this. */
public record ScanReport(
        String toolVersion,
        Instant startedAt,
        Duration duration,
        ProjectInfo project,
        DependencyGraph graph,
        List<Finding> findings,
        List<AnalyzerError> errors) {

    public ScanReport {
        findings = List.copyOf(findings);
        errors = List.copyOf(errors);
    }

    public Map<Severity, Long> countBySeverity() {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) counts.put(s, 0L);
        findings.forEach(f -> counts.merge(f.severity(), 1L, Long::sum));
        return counts;
    }

    public boolean hasFindingsAtLeast(Severity threshold) {
        return findings.stream().anyMatch(f -> f.severity().atLeast(threshold));
    }
}
