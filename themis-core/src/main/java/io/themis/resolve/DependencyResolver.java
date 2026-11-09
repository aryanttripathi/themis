package io.themis.resolve;

import io.themis.config.ScanConfig;

import java.nio.file.Path;

/** Models a project and resolves its dependency graph, for one build system (Maven now, Gradle later). */
public interface DependencyResolver {

    /** True if this resolver understands the project (e.g. a pom.xml is present). */
    boolean supports(Path projectDir);

    /**
     * Opens a per-scan session on the project: builds its model (and, for multi-module builds,
     * every module's) so {@link ProjectSession#project()} and {@link ProjectSession#resolveDependencies()}
     * share it.
     *
     * @throws IllegalArgumentException if the directory holds no acceptable build file
     * @throws ResolutionException      if the project model cannot be built (e.g. parent POM not found)
     */
    ProjectSession open(Path projectDir, ScanConfig config);
}
