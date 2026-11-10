package io.themis.config;

import io.themis.model.LicenseCategory;
import io.themis.model.Severity;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A parsed and validated .themis.yml: only the keys present in the file are set, so
 * {@link #applyTo} changes nothing else. Precedence is built by layering:
 * {@code cliFlags(file.applyTo(ScanConfig.defaults()))}.
 *
 * @param licenseSeverities categories given a severity in {@code licenses.severity}
 * @param silencedLicenses  categories set to {@code NONE} (no finding)
 * @param overrides         {@code g:a[:v]} -> SPDX expression (already normalised)
 */
public record ConfigFile(
        Path source,
        OptionalInt javaRelease,
        Optional<Set<String>> analyzers,
        Optional<Severity> failOn,
        Map<LicenseCategory, Severity> licenseSeverities,
        Set<LicenseCategory> silencedLicenses,
        Map<String, String> overrides,
        Set<String> allowArtifacts,
        Optional<Boolean> offline,
        OptionalInt cacheTtlHours,
        Optional<io.themis.privacy.PrivacyConfig> privacy,
        Optional<Integer> consoleLimit,
        Optional<Boolean> openReport) {

    public ConfigFile {
        analyzers = analyzers.map(Set::copyOf);
        licenseSeverities = Map.copyOf(licenseSeverities);
        silencedLicenses = Set.copyOf(silencedLicenses);
        overrides = Map.copyOf(overrides);
        allowArtifacts = Set.copyOf(allowArtifacts);
    }

    /** A file without any setting. */
    public static ConfigFile empty(Path source) {
        return new ConfigFile(source, OptionalInt.empty(), Optional.empty(), Optional.empty(), Map.of(), Set.of(),
                Map.of(), Set.of(), Optional.empty(), OptionalInt.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * {@code base} with this file's settings on top. Within {@code licenses}: listed categories
     * replace their default severity (others keep it), overrides are merged, allowArtifacts added.
     */
    public ScanConfig applyTo(ScanConfig base) {
        ScanConfig c = base;
        if (javaRelease.isPresent()) c = c.withJavaRelease(javaRelease);
        if (analyzers.isPresent()) c = c.withEnabledAnalyzers(analyzers.get());
        if (failOn.isPresent()) c = c.withFailOn(failOn.get());
        if (offline.isPresent()) c = c.withOffline(offline.get());
        if (cacheTtlHours.isPresent()) c = c.withCacheTtlHours(cacheTtlHours);
        if (privacy.isPresent()) c = c.withPrivacy(privacy.get());

        if (!licenseSeverities.isEmpty() || !silencedLicenses.isEmpty() || !overrides.isEmpty() || !allowArtifacts.isEmpty()) {
            LicensePolicy p = c.licenses();
            Map<LicenseCategory, Severity> severities = new EnumMap<>(LicenseCategory.class);
            severities.putAll(p.severities());
            severities.putAll(licenseSeverities);
            silencedLicenses.forEach(severities::remove);
            Map<String, String> mergedOverrides = new HashMap<>(p.overrides());
            mergedOverrides.putAll(overrides);
            Set<String> allow = new HashSet<>(p.allowArtifacts());
            allow.addAll(allowArtifacts);
            c = c.withLicensePolicy(new LicensePolicy(severities, mergedOverrides, allow));
        }
        return c;
    }
}
