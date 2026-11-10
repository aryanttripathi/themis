package io.themis.resolve;

import io.themis.config.ScanConfig;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;

import java.nio.file.Path;

/**
 * Takes project facts from another resolver but reports no dependencies. Useful as a test double
 * and for running source-only analyzers without dependency resolution.
 */
public final class EmptyDependencyResolver implements DependencyResolver {

    private final DependencyResolver projectFacts;

    public EmptyDependencyResolver(DependencyResolver projectFacts) {
        this.projectFacts = projectFacts;
    }

    @Override
    public boolean supports(Path projectDir) {
        return projectFacts.supports(projectDir);
    }

    @Override
    public ProjectSession open(Path projectDir, ScanConfig config) {
        ProjectSession delegate = projectFacts.open(projectDir, config);
        return new ProjectSession() {
            @Override
            public ProjectInfo project() {
                return delegate.project();
            }

            @Override
            public DependencyGraph resolveDependencies() {
                return DependencyGraph.empty(delegate.project().coordinate());
            }

            @Override
            public PomLicenses pomLicenses(io.themis.model.Coordinate dependency) {
                return delegate.pomLicenses(dependency);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }
}
