package io.themis;

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
public record Environment(ResolverOptions resolver, Path cacheDir, URI depsDevBaseUrl) {

    public Environment {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(cacheDir, "cacheDir");
        Objects.requireNonNull(depsDevBaseUrl, "depsDevBaseUrl");
    }

    /** Maven defaults, {@code ~/.themis/cache} (or {@code -Dthemis.cache.dir}), https://api.deps.dev/. */
    public static Environment defaults() {
        return new Environment(ResolverOptions.defaults(), FileCache.defaultDirectory(), DepsDevClient.DEFAULT_BASE_URL);
    }

    public Environment withResolver(ResolverOptions options) {
        return new Environment(options, cacheDir, depsDevBaseUrl);
    }
}
