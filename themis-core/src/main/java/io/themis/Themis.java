package io.themis;

import io.themis.config.ScanConfig;
import io.themis.engine.Analyzer;
import io.themis.engine.ScanEngine;
import io.themis.javaversion.JavaVersionAnalyzer;
import io.themis.license.LicenseAnalyzer;
import io.themis.model.LicenseInfo;
import io.themis.model.DependencyGraph;
import io.themis.model.ScanReport;
import io.themis.resolve.ProjectSession;
import io.themis.resolve.maven.MavenDependencyResolver;
import io.themis.resolve.maven.ResolverOptions;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Analyzers wired by default, using the production {@link Environment}. */
    public static List<Analyzer> defaultAnalyzers() {
        return defaultAnalyzers(Environment.defaults());
    }

    /** Analyzers wired by default. New analyzers are registered here as phases land. */
    public static List<Analyzer> defaultAnalyzers(Environment env) {
        return List.of(new JavaVersionAnalyzer(), LicenseAnalyzer.create(env.cacheDir(), env.depsDevBaseUrl()));
    }

    /** Names accepted by {@code --only} and the {@code analyzers} key of .themis.yml. */
    public static Set<String> analyzerNames() {
        return defaultAnalyzers(Environment.defaults()).stream().map(Analyzer::name).collect(Collectors.toUnmodifiableSet());
    }

    /** Engine with Maven's defaults (~/.m2/settings.xml, local repository), ~/.themis/cache and deps.dev. */
    public static ScanEngine defaultEngine() {
        return defaultEngine(Environment.defaults());
    }

    public static ScanEngine defaultEngine(Environment env) {
        return new ScanEngine(new MavenDependencyResolver(env.resolver()), defaultAnalyzers(env), VERSION);
    }

    /** Default environment, but with these Maven resolver options. */
    public static ScanEngine defaultEngine(ResolverOptions options) {
        return defaultEngine(Environment.defaults().withResolver(options));
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

    /**
     * Runs only the license analyzer, as {@code themis licenses} does; the {@link LicenseInfo} of every
     * dependency is in {@code report.dataOf(LicenseInfo.class)}.
     */
    public static ScanReport licenses(Path projectDir, ScanConfig config, Environment env) {
        return defaultEngine(env).scan(projectDir, config.withEnabledAnalyzers(Set.of(LicenseAnalyzer.NAME)));
    }
}
