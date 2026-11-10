package io.themis.license;

import com.fasterxml.jackson.core.type.TypeReference;
import io.themis.cache.FileCache;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.LicenseCategory;
import io.themis.model.LicenseInfo;
import io.themis.resolve.DeclaredLicense;
import io.themis.resolve.DependencyMetadata;
import io.themis.resolve.PomLicenses;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Finds the license of each dependency. First hit wins, the source is recorded:
 * <ol>
 *   <li>{@code config}: {@code licenses.overrides} for {@code g:a:v}, else {@code g:a};</li>
 *   <li>{@code pom} / {@code parent-pom}: the dependency's effective POM {@code <licenses>}
 *       (several entries = dual licensing, combined with {@code OR});</li>
 *   <li>{@code deps.dev}: the deps.dev v3 version endpoint, cached (namespace {@code license});
 *       offline: cache only;</li>
 *   <li>{@code jar:<entry>}: a license file inside the jar ({@link LicenseTextMatcher});</li>
 *   <li>{@code none}: UNKNOWN.</li>
 * </ol>
 * A source only "hits" if it yields at least one recognised SPDX id; unrecognised declarations
 * fall through to the next source and are kept in {@link LicenseInfo#declared()}. Unrecognised
 * alternatives next to recognised ones are ignored for classification (kept as declared).
 */
public final class LicenseLookup {

    public static final Duration DEFAULT_TTL = Duration.ofDays(30);
    public static final String CACHE_NAMESPACE = "license";
    /** Concurrent deps.dev requests: enough to be quick on 200+ dependencies, few enough to be polite. */
    static final int MAX_CONCURRENT_REQUESTS = 8;

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    /**
     * @param overrides {@code g:a[:v]} -> SPDX expression
     * @param offline   no deps.dev requests; cached answers are still used
     * @param ttl       maximum age of cached deps.dev answers
     */
    /**
     * @param mayQuery coordinates allowed to leave the machine (privacy: internal packages and
     *                 snapshots are never sent to deps.dev)
     */
    public record Settings(Map<String, String> overrides, boolean offline, Duration ttl,
                           java.util.function.Predicate<Coordinate> mayQuery) {
        public Settings {
            overrides = Map.copyOf(overrides);
        }

        public Settings(Map<String, String> overrides, boolean offline, Duration ttl) {
            this(overrides, offline, ttl, c -> true);
        }
    }

    private final SpdxNormalizer normalizer;
    private final LicenseClassifier classifier;
    private final DepsDevClient depsDev;
    private final FileCache cache;
    private final LicenseTextMatcher matcher;

    public LicenseLookup(SpdxNormalizer normalizer, LicenseClassifier classifier, DepsDevClient depsDev,
                         FileCache cache, LicenseTextMatcher matcher) {
        this.normalizer = normalizer;
        this.classifier = classifier;
        this.depsDev = depsDev;
        this.cache = cache;
        this.matcher = matcher;
    }

    /** Mutable working state for one dependency while it moves down the chain. */
    private static final class State {
        final Dependency dependency;
        final Set<String> declared = new LinkedHashSet<>();
        final Set<String> notes = new LinkedHashSet<>();
        LicenseInfo result;

        State(Dependency dependency) {
            this.dependency = dependency;
        }

        Coordinate coordinate() {
            return dependency.coordinate();
        }
    }

    /** @return one {@link LicenseInfo} per dependency, in the same order */
    public List<LicenseInfo> lookupAll(List<Dependency> dependencies, DependencyMetadata metadata, Settings settings) {
        List<State> states = dependencies.stream().map(State::new).toList();

        // 1-2: overrides and POMs (local; the POMs were fetched during resolution)
        for (State s : states) {
            PomLicenses pom = metadata.pomLicenses(s.coordinate());
            pom.licenses().forEach(l -> s.declared.add(l.display()));
            pom.problem().ifPresent(p -> s.notes.add("POM not readable: " + p));
            override(s, settings).ifPresent(spdx -> s.result = info(s, spdx, "config"));
            if (s.result == null) {
                combine(s, pom.licenses()).ifPresent(spdx -> s.result = info(s, spdx, pom.inherited() ? "parent-pom" : "pom"));
            }
        }

        // 3: deps.dev for the rest, with bounded concurrency
        List<State> pending = states.stream().filter(s -> s.result == null).toList();
        depsDevAll(pending, settings);

        // 4-5: jar text, else UNKNOWN
        for (State s : states) {
            if (s.result == null && s.dependency.jarFile().isPresent()) {
                matcher.match(s.dependency.jarFile().get())
                        .ifPresent(m -> s.result = info(s, m.spdx(), "jar:" + m.entry()));
            }
            if (s.result == null) {
                s.result = new LicenseInfo(s.coordinate(), Optional.empty(), LicenseCategory.UNKNOWN, "none",
                        List.copyOf(s.declared), List.copyOf(s.notes));
            }
        }
        return states.stream().map(s -> s.result).toList();
    }

    private Optional<String> override(State s, Settings settings) {
        Coordinate c = s.coordinate();
        String value = io.themis.config.LicensePolicy.overrideFor(settings.overrides(), c).orElse(null);
        if (value == null) return Optional.empty();
        Optional<SpdxNormalizer.Normalized> n = normalizer.normalize(value, null);
        if (n.isEmpty()) s.notes.add("licenses.overrides value '" + value + "' is not a recognised SPDX expression; ignored");
        return n.map(SpdxNormalizer.Normalized::spdx);
    }

    /** Recognised entries joined with OR (dual licensing); empty if none is recognised. */
    private Optional<String> combine(State s, List<DeclaredLicense> licenses) {
        Set<String> ids = new LinkedHashSet<>();
        for (DeclaredLicense l : licenses) {
            normalizer.normalize(l.name(), l.url()).ifPresent(n -> {
                ids.add(n.spdx());
                if (n.ambiguous()) {
                    s.notes.add("'" + l.display() + "' names only a license family; assumed " + n.spdx());
                }
            });
        }
        return or(ids);
    }

    private static Optional<String> or(Set<String> ids) {
        if (ids.isEmpty()) return Optional.empty();
        if (ids.size() == 1) return Optional.of(ids.iterator().next());
        List<String> parts = ids.stream().map(id -> id.contains(" AND ") ? "(" + id + ")" : id).toList();
        return Optional.of(String.join(" OR ", parts));
    }

    private void depsDevAll(List<State> pending, Settings settings) {
        if (pending.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT_REQUESTS, pending.size()), r -> {
            Thread t = new Thread(r, "themis-deps.dev");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (State s : pending) futures.add(pool.submit(() -> depsDev(s, settings)));
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw new IllegalStateException("deps.dev lookup failed: " + e.getCause().getMessage(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while querying deps.dev", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void depsDev(State s, Settings settings) {
        Coordinate c = s.coordinate();
        if (!settings.mayQuery().test(c)) {
            s.notes.add("not looked up on deps.dev: private coordinate");
            return;
        }
        String key = c.ga() + ":" + c.version();
        Optional<List<String>> answer = cache.get(CACHE_NAMESPACE, key, settings.ttl(), STRINGS);
        if (answer.isEmpty() && !settings.offline()) {
            try {
                List<String> fetched = depsDev.licenses(new Coordinate(c.groupId(), c.artifactId(), c.version()))
                        .orElse(List.of()); // 404: deps.dev knows no license for it, also worth caching
                cache.put(CACHE_NAMESPACE, key, fetched);
                answer = Optional.of(fetched);
            } catch (IOException e) {
                s.notes.add("deps.dev unavailable: " + e.getMessage()); // not cached: retried next scan
            }
        }
        if (answer.isEmpty()) return;
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : answer.get()) {
            s.declared.add(raw + " (deps.dev)");
            normalizer.normalize(raw, null).ifPresent(n -> ids.add(n.spdx()));
        }
        or(ids).ifPresent(spdx -> s.result = info(s, spdx, "deps.dev"));
    }

    private LicenseInfo info(State s, String spdx, String source) {
        return new LicenseInfo(s.coordinate(), Optional.of(spdx), classifier.classify(spdx), source,
                List.copyOf(s.declared), List.copyOf(s.notes));
    }
}
