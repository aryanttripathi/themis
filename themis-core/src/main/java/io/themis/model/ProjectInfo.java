package io.themis.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Facts about the scanned project.
 *
 * @param javaRelease the Java release the project targets (e.g. 17)
 */
public record ProjectInfo(
        Path rootDir,
        Coordinate coordinate,
        int javaRelease,
        List<Path> sourceRoots,
        List<Path> resourceRoots) {

    public ProjectInfo {
        sourceRoots = List.copyOf(sourceRoots);
        resourceRoots = List.copyOf(resourceRoots);
    }
}
