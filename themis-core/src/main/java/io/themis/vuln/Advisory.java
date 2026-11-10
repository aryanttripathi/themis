package io.themis.vuln;

import io.themis.model.Severity;

import java.util.List;
import java.util.Optional;

/**
 * One known vulnerability affecting a dependency.
 *
 * @param fixedVersions every "fixed" version OSV lists for this package (all branches)
 * @param fixedIn       nearest fixed version above the dependency's current version
 */
public record Advisory(String id, List<String> aliases, String summary, Severity severity, Optional<Double> cvss,
                       List<String> fixedVersions, Optional<String> fixedIn, String url) {

    public Advisory {
        aliases = List.copyOf(aliases);
        fixedVersions = List.copyOf(fixedVersions);
    }
}
