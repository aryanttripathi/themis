package io.themis.config;

import io.themis.model.LicenseCategory;
import io.themis.model.Scope;
import io.themis.model.Severity;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * License policy (the {@code licenses:} section of .themis.yml).
 *
 * @param severities     finding severity per category; a category without an entry produces no finding
 * @param overrides      {@code "g:a"} or {@code "g:a:v"} -> SPDX expression, used instead of any detected license
 * @param allowArtifacts {@code "g:a"} whose license findings are suppressed (their license is still reported)
 */
public record LicensePolicy(Map<LicenseCategory, Severity> severities, Map<String, String> overrides,
                            Set<String> allowArtifacts) {

    public LicensePolicy {
        severities = Map.copyOf(severities);
        overrides = Map.copyOf(overrides);
        allowArtifacts = Set.copyOf(allowArtifacts);
    }

    /** NETWORK/STRONG copyleft HIGH, PROPRIETARY MEDIUM, UNKNOWN LOW, WEAK copyleft INFO, PERMISSIVE none. */
    public static LicensePolicy defaults() {
        Map<LicenseCategory, Severity> s = new EnumMap<>(LicenseCategory.class);
        s.put(LicenseCategory.NETWORK_COPYLEFT, Severity.HIGH);
        s.put(LicenseCategory.STRONG_COPYLEFT, Severity.HIGH);
        s.put(LicenseCategory.PROPRIETARY, Severity.MEDIUM);
        s.put(LicenseCategory.UNKNOWN, Severity.LOW);
        s.put(LicenseCategory.WEAK_COPYLEFT, Severity.INFO);
        return new LicensePolicy(s, Map.of(), Set.of());
    }

    /**
     * Severity of a finding for this category at this scope, or empty for no finding.
     * <ul>
     *   <li>{@code test}: never distributed, so at most INFO.</li>
     *   <li>{@code provided}/{@code system}: supplied by the runtime environment rather than shipped
     *       by you, so distribution-triggered obligations usually fall on the environment's vendor;
     *       they still run in production (network copyleft, unknown terms), so lowered one level, not capped.</li>
     * </ul>
     */
    public Optional<Severity> severityFor(LicenseCategory category, Scope scope) {
        Severity base = severities.get(category);
        if (base == null) return Optional.empty();
        return Optional.of(switch (scope) {
            case TEST -> Severity.INFO;
            case PROVIDED, SYSTEM -> base == Severity.INFO ? Severity.INFO : Severity.values()[base.ordinal() - 1];
            default -> base;
        });
    }

    /** Exact {@code g:a} or a {@code *} pattern, e.g. {@code com.mysql:*}. */
    public boolean isAllowed(String ga) {
        return allowArtifacts.contains(ga) || allowArtifacts.stream().anyMatch(p -> p.contains("*") && matches(p, ga));
    }

    /**
     * Override for a dependency: exact {@code g:a:v}, then exact {@code g:a}, then the most specific
     * {@code *} pattern (longest literal text), e.g. {@code com.acme*} or {@code com.acme.platform:*-client}.
     * {@code *} matches any characters, including {@code :}; everything else is literal.
     */
    public static java.util.Optional<String> overrideFor(Map<String, String> overrides, io.themis.model.Coordinate c) {
        String exact = overrides.getOrDefault(c.ga() + ":" + c.version(), overrides.get(c.ga()));
        if (exact != null) return java.util.Optional.of(exact);
        return overrides.entrySet().stream()
                .filter(e -> e.getKey().contains("*"))
                .filter(e -> matches(e.getKey(), c.ga()) || matches(e.getKey(), c.ga() + ":" + c.version()))
                .max(java.util.Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().replace("*", "").length())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getValue);
    }

    public static boolean matches(String pattern, String value) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) regex.append(".*");
            regex.append(java.util.regex.Pattern.quote(part));
        }
        return value.matches(regex.toString());
    }

    public LicensePolicy withSeverities(Map<LicenseCategory, Severity> s) {
        return new LicensePolicy(s, overrides, allowArtifacts);
    }

    public LicensePolicy withOverrides(Map<String, String> o) {
        return new LicensePolicy(severities, o, allowArtifacts);
    }

    public LicensePolicy withAllowArtifacts(Set<String> a) {
        return new LicensePolicy(severities, overrides, a);
    }
}
