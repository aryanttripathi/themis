package io.themis.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A resolved dependency (one artifact at one version).
 *
 * @param coordinate the resolved coordinate (after Maven version mediation), incl. extension/classifier
 * @param scope      Maven scope; when several modules of a build reach it, the widest one
 * @param direct     true if declared in the POM of (one of) the project's module(s)
 * @param path       chain from the declaring module to this dependency ("pulled in via"); for a
 *                   dependency reached by several modules, the most direct/shortest such chain
 * @param jarFile    resolved artifact on disk, if it is a jar
 * @param modules    the project modules whose resolved graph contains this artifact at this version
 *                   (just the project itself for a single-module build)
 */
public record Dependency(
        Coordinate coordinate,
        Scope scope,
        boolean direct,
        List<Coordinate> path,
        Optional<Path> jarFile,
        List<Coordinate> modules) {

    public Dependency {
        path = List.copyOf(path);
        modules = List.copyOf(modules);
    }

    /** Single-module convenience: the declaring module is the first element of {@code path}. */
    public Dependency(Coordinate coordinate, Scope scope, boolean direct, List<Coordinate> path, Optional<Path> jarFile) {
        this(coordinate, scope, direct, path, jarFile, path.isEmpty() ? List.of() : List.of(path.get(0)));
    }
}
