package io.themis.source;

import java.util.Set;

/**
 * Settings for the source-code analyzers (secrets, PII).
 *
 * @param exclude         glob patterns (relative to the project root) never scanned, e.g. {@code legacy/**}
 * @param extraPiiFields  identifiers that are PII in this codebase (e.g. {@code customerId})
 * @param ignorePiiFields identifiers never treated as PII (false positives)
 */
public record SourceConfig(Set<String> exclude, Set<String> extraPiiFields, Set<String> ignorePiiFields) {

    public SourceConfig {
        exclude = Set.copyOf(exclude);
        extraPiiFields = Set.copyOf(extraPiiFields);
        ignorePiiFields = Set.copyOf(ignorePiiFields);
    }

    public static SourceConfig defaults() {
        return new SourceConfig(Set.of(), Set.of(), Set.of());
    }
}
