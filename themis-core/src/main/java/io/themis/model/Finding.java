package io.themis.model;

import java.util.Map;
import java.util.Optional;

/**
 * One reportable issue.
 *
 * @param id       stable fingerprint used for suppressions and baselines
 * @param evidence where the conclusion came from (e.g. source=pom, spdx=AGPL-3.0-only)
 */
public record Finding(
        String id,
        FindingType type,
        Severity severity,
        String title,
        String detail,
        Optional<Coordinate> dependency,
        Optional<Location> location,
        Map<String, String> evidence) {

    public Finding {
        evidence = Map.copyOf(evidence);
    }

    /** Convenience factory for dependency-level findings. */
    public static Finding forDependency(String ruleId, FindingType type, Severity severity,
                                        String title, String detail, Coordinate dep,
                                        Map<String, String> evidence) {
        String id = type + ":" + ruleId + ":" + dep.ga();
        return new Finding(id, type, severity, title, detail, Optional.of(dep), Optional.empty(), evidence);
    }
}
