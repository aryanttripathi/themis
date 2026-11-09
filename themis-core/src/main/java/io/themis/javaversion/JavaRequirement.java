package io.themis.javaversion;

import io.themis.model.Coordinate;

/** Minimum Java release a dependency needs, read from its bytecode. */
public record JavaRequirement(Coordinate dependency, int requiredRelease, boolean multiRelease) {
}
