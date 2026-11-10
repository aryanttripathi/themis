package io.themis.vuln;

import io.themis.model.Coordinate;

import java.util.List;
import java.util.Optional;

/**
 * Vulnerability check result for one dependency.
 *
 * @param fixedIn    smallest version that fixes every advisory with a known fix
 * @param notChecked why the dependency was not checked (private, offline without cache), if so
 */
public record DependencyVulnerabilities(Coordinate dependency, List<Advisory> advisories, Optional<String> fixedIn,
                                        Optional<String> notChecked) {

    public DependencyVulnerabilities {
        advisories = List.copyOf(advisories);
    }
}
