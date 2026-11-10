package io.themis.resolve;

import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;

/**
 * One scan's view of a project, opened by {@link DependencyResolver#open}. Inspection and resolution
 * share the same build-tool state (for Maven: one resolver session, the reactor and its effective
 * models), so the project is modelled once. It stays open while analyzers run, so they can ask for
 * {@link DependencyMetadata} (e.g. POM licenses). Close it when done.
 */
public interface ProjectSession extends DependencyMetadata, AutoCloseable {

    /** Project facts (coordinate, Java release, source roots) from the effective build model. */
    ProjectInfo project();

    /**
     * Resolves the full dependency graph (direct and transitive).
     *
     * @throws ResolutionException naming the unresolvable artifacts
     */
    DependencyGraph resolveDependencies();

    /** None by default; build-tool sessions that read dependency descriptors override this. */
    @Override
    default PomLicenses pomLicenses(io.themis.model.Coordinate dependency) {
        return PomLicenses.NONE;
    }

    @Override
    void close();
}
