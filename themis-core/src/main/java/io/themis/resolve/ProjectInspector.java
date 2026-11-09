package io.themis.resolve;

import io.themis.config.ScanConfig;
import io.themis.model.ProjectInfo;
import io.themis.resolve.maven.MavenDependencyResolver;
import io.themis.resolve.maven.ResolverOptions;

import java.nio.file.Path;

/**
 * Convenience for library users who only need project facts (coordinate, Java release, source
 * roots) without resolving dependencies. The scan engine does not use it: it gets the same facts
 * from its {@link ProjectSession}, sharing one model build with resolution.
 */
public final class ProjectInspector {

    public static final int DEFAULT_JAVA_RELEASE = JavaRelease.DEFAULT;

    private final DependencyResolver resolver;

    /** Uses ~/.m2/settings.xml and the other Maven defaults to fetch remote parent POMs. */
    public ProjectInspector() {
        this(ResolverOptions.defaults());
    }

    public ProjectInspector(ResolverOptions options) {
        this(new MavenDependencyResolver(options));
    }

    public ProjectInspector(DependencyResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * @throws IllegalArgumentException if there is no pom.xml or it is not acceptable XML
     * @throws ResolutionException      if the effective model cannot be built (e.g. missing parent)
     */
    public ProjectInfo inspect(Path projectDir, ScanConfig config) {
        try (ProjectSession session = resolver.open(projectDir, config)) {
            return session.project();
        }
    }
}
