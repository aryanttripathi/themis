package io.themis;

import io.themis.resolve.AutoDetectingResolver;
import io.themis.resolve.DependencyResolver;
import io.themis.resolve.gradle.GradleDependencyResolver;
import io.themis.resolve.gradle.GradleOptions;
import io.themis.resolve.maven.MavenDependencyResolver;
import java.util.List;

import io.themis.cache.FileCache;
import io.themis.license.DepsDevClient;
import io.themis.resolve.maven.ResolverOptions;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Everything a scan takes from the machine it runs on: Maven settings/local repository, the cache
 * directory and the deps.dev endpoint. Production uses {@link #defaults()}; tests pass fixture
 * settings, a temp cache and a local HTTP stub, so nothing leaks in from the real environment.
 *
 * @param resolver       Maven resolver environment
 * @param cacheDir       root of the network-answer cache ({@code ~/.themis/cache})
 * @param depsDevBaseUrl deps.dev API base URL
 */
public record Environment(ResolverOptions resolver, Path cacheDir, URI depsDevBaseUrl, GradleOptions gradle, URI osvBaseUrl) {

    public Environment {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(depsDevBaseUrl, "depsDevBaseUrl");
        Objects.requireNonNull(gradle, "gradle");
        Objects.requireNonNull(osvBaseUrl, "osvBaseUrl");
    }

    /** Production OSV endpoint. */
    public Environment(ResolverOptions resolver, Path cacheDir, URI depsDevBaseUrl, GradleOptions gradle) {
        this(resolver, cacheDir, depsDevBaseUrl, gradle, io.themis.vuln.OsvClient.DEFAULT_BASE_URL);
    }

    /** Default Gradle options (project wrapper, the user's GRADLE_USER_HOME). */
    public Environment(ResolverOptions resolver, Path cacheDir, URI depsDevBaseUrl) {
        this(resolver, cacheDir, depsDevBaseUrl, GradleOptions.defaults());
    }

    /** Maven and Gradle resolution, chosen per project (pom.xml wins). */
    public DependencyResolver dependencyResolver() {
        return new AutoDetectingResolver(List.of(new MavenDependencyResolver(resolver), new GradleDependencyResolver(gradle)));
    }

    /** Maven defaults, {@code ~/.themis/cache} (or {@code -Dthemis.cache.dir}), https://api.deps.dev/. */
    public static Environment defaults() {
        return new Environment(ResolverOptions.defaults(), FileCache.defaultDirectory(), DepsDevClient.DEFAULT_BASE_URL);
    }

    public Environment withResolver(ResolverOptions options) {
        return new Environment(options, cacheDir, depsDevBaseUrl, gradle, osvBaseUrl);
    }
}
