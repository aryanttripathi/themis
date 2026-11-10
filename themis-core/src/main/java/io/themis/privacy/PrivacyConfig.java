package io.themis.privacy;

import java.util.Objects;
import java.util.Set;

/**
 * What must never be sent to public APIs (OSV, deps.dev).
 *
 * @param privateArtifacts    {@code g:a} / {@code g:a:v} / {@code *} patterns of internal packages
 * @param projectGroupPrivate treat the project's own organisation group (e.g. {@code com.acme*}) as private
 */
public record PrivacyConfig(Set<String> privateArtifacts, boolean projectGroupPrivate) {

    public PrivacyConfig {
        privateArtifacts = Set.copyOf(Objects.requireNonNull(privateArtifacts, "privateArtifacts"));
    }

    public static PrivacyConfig defaults() {
        return new PrivacyConfig(Set.of(), true);
    }
}
