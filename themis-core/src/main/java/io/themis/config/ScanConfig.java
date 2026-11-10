package io.themis.config;

import io.themis.model.Severity;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Effective configuration for one scan (CLI flags merged over .themis.yml over defaults; see
 * {@link ConfigFile#applyTo}).
 *
 * @param enabledAnalyzers analyzer names to run; empty means all
 * @param failOn           exit with failure if any finding is at or above this severity
 * @param javaRelease      overrides the Java release detected from the POM
 * @param offline          no network: Maven offline mode, and deps.dev answers only from the cache
 * @param licenses         license policy
 * @param cacheTtlHours    overrides the per-namespace TTL of cached network answers (license: 30 days)
 * @param lenient          unresolvable dependencies become warnings instead of failing the scan
 * @param privacy          which coordinates must never be sent to public APIs
 */
public record ScanConfig(
        Set<String> enabledAnalyzers,
        Severity failOn,
        OptionalInt javaRelease,
        boolean offline,
        boolean includeTestScope,
        LicensePolicy licenses,
        OptionalInt cacheTtlHours,
        boolean lenient,
        io.themis.privacy.PrivacyConfig privacy) {

    public ScanConfig {
        enabledAnalyzers = Set.copyOf(enabledAnalyzers);
        Objects.requireNonNull(failOn, "failOn");
        Objects.requireNonNull(licenses, "licenses");
        Objects.requireNonNull(cacheTtlHours, "cacheTtlHours");
        Objects.requireNonNull(privacy, "privacy");
    }

    /** Default license policy and cache TTLs. */
    public ScanConfig(Set<String> enabledAnalyzers, Severity failOn, OptionalInt javaRelease,
                      boolean offline, boolean includeTestScope) {
        this(enabledAnalyzers, failOn, javaRelease, offline, includeTestScope, LicensePolicy.defaults(), OptionalInt.empty(), false, io.themis.privacy.PrivacyConfig.defaults());
    }

    public static ScanConfig defaults() {
        return new ScanConfig(Set.of(), Severity.HIGH, OptionalInt.empty(), false, false);
    }

    public boolean isEnabled(String analyzerName) {
        return enabledAnalyzers.isEmpty() || enabledAnalyzers.contains(analyzerName);
    }

    public ScanConfig withEnabledAnalyzers(Set<String> analyzers) {
        return new ScanConfig(analyzers, failOn, javaRelease, offline, includeTestScope, licenses, cacheTtlHours, lenient, privacy);
    }

    public ScanConfig withFailOn(Severity severity) {
        return new ScanConfig(enabledAnalyzers, severity, javaRelease, offline, includeTestScope, licenses, cacheTtlHours, lenient, privacy);
    }

    public ScanConfig withJavaRelease(OptionalInt release) {
        return new ScanConfig(enabledAnalyzers, failOn, release, offline, includeTestScope, licenses, cacheTtlHours, lenient, privacy);
    }

    public ScanConfig withOffline(boolean value) {
        return new ScanConfig(enabledAnalyzers, failOn, javaRelease, value, includeTestScope, licenses, cacheTtlHours, lenient, privacy);
    }

    public ScanConfig withIncludeTestScope(boolean value) {
        return new ScanConfig(enabledAnalyzers, failOn, javaRelease, offline, value, licenses, cacheTtlHours, lenient, privacy);
    }

    public ScanConfig withLicensePolicy(LicensePolicy policy) {
        return new ScanConfig(enabledAnalyzers, failOn, javaRelease, offline, includeTestScope, policy, cacheTtlHours, lenient, privacy);
    }

    public ScanConfig withLenient(boolean value) {
        return new ScanConfig(enabledAnalyzers, failOn, javaRelease, offline, includeTestScope, licenses, cacheTtlHours, value, privacy);
    }

    public ScanConfig withPrivacy(io.themis.privacy.PrivacyConfig value) {
        return new ScanConfig(enabledAnalyzers, failOn, javaRelease, offline, includeTestScope, licenses, cacheTtlHours, lenient, value);
    }

    public ScanConfig withCacheTtlHours(OptionalInt hours) {
        return new ScanConfig(enabledAnalyzers, failOn, javaRelease, offline, includeTestScope, licenses, hours, lenient, privacy);
    }
}
