package io.themis;

import io.themis.config.ScanConfig;
import io.themis.engine.Analyzer;
import io.themis.engine.ScanEngine;
import io.themis.javaversion.JavaVersionAnalyzer;
import io.themis.model.DependencyGraph;
import io.themis.model.ScanReport;
import io.themis.resolve.ProjectSession;
import io.themis.resolve.maven.MavenDependencyResolver;
import io.themis.resolve.maven.ResolverOptions;

import java.nio.file.Path;
import java.util.List;

/**
 * Library entry point.
 * <pre>{@code
 * ScanReport report = Themis.scan(Path.of("."), ScanConfig.defaults());
 * }</pre>
 */
public final class Themis {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    private Themis() {
    }

    /** Analyzers wired by default. New analyzers are registered here as phases land. */
    public static List<Analyzer> defaultAnalyzers() {
        return List.of(new JavaVersionAnalyzer());
    }

    /** Engine resolving with Maven's defaults (~/.m2/settings.xml, local repository). */
    public static ScanEngine defaultEngine() {
        return defaultEngine(ResolverOptions.defaults());
    }

    public static ScanEngine defaultEngine(ResolverOptions options) {
        return new ScanEngine(new MavenDependencyResolver(options), defaultAnalyzers(), VERSION);
    }

    public static ScanReport scan(Path projectDir, ScanConfig config) {
        return defaultEngine().scan(projectDir, config);
    }

    /**
     * Resolves the project's dependency graph only (no analyzers), as {@code themis deps} prints it.
     *
     * @throws io.themis.resolve.ResolutionException if dependencies cannot be resolved
     */
    public static DependencyGraph resolve(Path projectDir, ScanConfig config, ResolverOptions options) {
        try (ProjectSession session = new MavenDependencyResolver(options).open(projectDir, config)) {
            return session.resolveDependencies();
        }
    }
}
