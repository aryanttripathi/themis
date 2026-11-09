package io.themis.resolve;

import io.themis.config.ScanConfig;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;

import java.nio.file.Path;

/** Builds the resolved dependency graph for one build system (Maven now, Gradle later). */
public interface DependencyResolver {

    /** True if this resolver understands the project (e.g. a pom.xml is present). */
    boolean supports(Path projectDir);

    DependencyGraph resolve(ProjectInfo project, ScanConfig config);
}
