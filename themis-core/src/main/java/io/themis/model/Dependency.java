package io.themis.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A resolved dependency.
 *
 * @param coordinate the resolved coordinate (after Maven version mediation)
 * @param scope      Maven scope
 * @param direct     true if declared in the project's own POM
 * @param path       chain from the project root to this dependency ("pulled in via")
 * @param jarFile    resolved artifact on disk, if available
 */
public record Dependency(
        Coordinate coordinate,
        Scope scope,
        boolean direct,
        List<Coordinate> path,
        Optional<Path> jarFile) {

    public Dependency {
        path = List.copyOf(path);
    }
}
