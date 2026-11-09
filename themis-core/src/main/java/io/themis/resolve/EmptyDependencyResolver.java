package io.themis.resolve;

import io.themis.config.ScanConfig;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;

import java.nio.file.Path;

/**
 * Phase-1 placeholder that returns no dependencies, so the pipeline runs end to end
 * before {@code MavenDependencyResolver} (Maven Resolver based) lands. See docs/LLD.md section 4.
 */
public final class EmptyDependencyResolver implements DependencyResolver {

    @Override
    public boolean supports(Path projectDir) {
        return true;
    }

    @Override
    public DependencyGraph resolve(ProjectInfo project, ScanConfig config) {
        return DependencyGraph.empty(project.coordinate());
    }
}
