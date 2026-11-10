package io.themis.upgrade;

import io.themis.model.Coordinate;

import java.util.Optional;

/**
 * Upgrade advice for one vulnerable dependency.
 *
 * @param recommended         lowest stable version with no known vulnerabilities that runs on the project's Java
 * @param requiredJavaRelease Java release the recommended version's bytecode needs (empty if unknown)
 * @param sameMajor           the recommendation stays on the current major version
 * @param viaDirect           for a transitive dependency: the direct dependency that pulls it in
 * @param fixes               advisories the upgrade resolves
 * @param note                why there is no recommendation, or a caveat; empty if none
 */
public record UpgradeSuggestion(Coordinate current, Optional<String> recommended, Optional<Integer> requiredJavaRelease,
                                boolean sameMajor, Optional<Coordinate> viaDirect, int fixes, String note) {
}
