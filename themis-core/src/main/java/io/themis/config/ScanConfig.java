package io.themis.config;

import io.themis.model.Severity;

import java.util.OptionalInt;
import java.util.Set;

/**
 * Effective configuration for one scan (CLI flags merged over .themis.yml over defaults).
 *
 * @param enabledAnalyzers analyzer names to run; empty means all
 * @param failOn           exit with failure if any finding is at or above this severity
 * @param javaRelease      overrides the Java release detected from the POM
 */
public record ScanConfig(
        Set<String> enabledAnalyzers,
        Severity failOn,
        OptionalInt javaRelease,
        boolean offline,
        boolean includeTestScope) {

    public ScanConfig {
        enabledAnalyzers = Set.copyOf(enabledAnalyzers);
    }

    public static ScanConfig defaults() {
        return new ScanConfig(Set.of(), Severity.HIGH, OptionalInt.empty(), false, false);
    }

    public boolean isEnabled(String analyzerName) {
        return enabledAnalyzers.isEmpty() || enabledAnalyzers.contains(analyzerName);
    }
}
