package io.themis.resolve.maven;

import io.themis.config.ScanConfig;
import io.themis.resolve.DependencyResolver;
import io.themis.resolve.ProjectSession;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a Maven project's dependency graph with Maven's own machinery (Maven Resolver +
 * maven-resolver-provider + maven-model-builder), so the result matches {@code mvn dependency:list}:
 * effective POM (parents, properties, settings profiles, dependencyManagement, BOM imports),
 * nearest-wins mediation with conflict losers omitted, scope derivation, optional and
 * {@code <exclusions>} handling, classifiers and types kept apart.
 * <p>
 * Scopes: {@code compile}, {@code runtime}, {@code provided} and {@code system} by default (all can
 * carry vulnerabilities; later analyzers decide how much each matters), plus {@code test} when
 * {@link ScanConfig#includeTestScope()}.
 * <p>
 * Multi-module builds ({@code <modules>}) are loaded as a reactor (siblings come from a workspace,
 * never from a repository) and merged per artifact and version; see {@link MavenProjectSession}.
 */
public final class MavenDependencyResolver implements DependencyResolver {

    private final ResolverOptions options;

    public MavenDependencyResolver(ResolverOptions options) {
        this.options = options;
    }

    /** Uses ~/.m2/settings.xml and the other Maven defaults (see {@link ResolverOptions#defaults()}). */
    public MavenDependencyResolver() {
        this(ResolverOptions.defaults());
    }

    @Override
    public boolean supports(Path projectDir) {
        return Files.isRegularFile(projectDir.resolve("pom.xml"));
    }

    @Override
    public ProjectSession open(Path projectDir, ScanConfig config) {
        return MavenProjectSession.open(options, projectDir, config);
    }
}
