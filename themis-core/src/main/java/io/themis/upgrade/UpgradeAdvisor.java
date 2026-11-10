package io.themis.upgrade;

import com.fasterxml.jackson.core.type.TypeReference;
import io.themis.cache.FileCache;
import io.themis.engine.Analyzer;
import io.themis.engine.AnalyzerResult;
import io.themis.engine.ScanContext;
import io.themis.engine.Stage;
import io.themis.javaversion.ClassFileVersion;
import io.themis.license.DepsDevClient;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.Severity;
import io.themis.privacy.LookupPrivacy;
import io.themis.vuln.Advisory;
import io.themis.vuln.DependencyVulnerabilities;
import io.themis.vuln.OsvClient;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * For each vulnerable dependency: the lowest stable newer version with no known vulnerabilities
 * whose bytecode runs on the project's Java release.
 * <ol>
 *   <li>versions from deps.dev, newer and stable only (no alpha/beta/RC/milestone/snapshot)</li>
 *   <li>the first {@value #WINDOW} candidates checked against OSV in one batch</li>
 *   <li>clean candidates in ascending order: the jar's class-file version decides Java compatibility</li>
 * </ol>
 * Same privacy rules as the other network analyzers: private coordinates are never looked up.
 */
public final class UpgradeAdvisor implements Analyzer {

    public static final String NAME = "upgrade";
    static final int WINDOW = 300;   // one OSV batch call; libraries like jackson ship dozens of patch releases
    static final int MAX_JAR_CHECKS = 3;
    private static final Pattern PRE_RELEASE = Pattern.compile(
            "(?i).*([.-](alpha|beta|rc|cr|m|milestone|preview|ea|snapshot|incubating|dev)[.-]?\\d*)([.-].*)?$|.*-\\d{8}\\.\\d{6}-\\d+$");
    private static final Duration DAY = Duration.ofHours(24);
    private static final Duration FOREVER = Duration.ofDays(3650);
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };
    private static final TypeReference<Integer> INT = new TypeReference<>() { };

    private final DepsDevClient depsDev;
    private final OsvClient osv;
    private final JarFetcher jars;
    private final FileCache cache;

    public UpgradeAdvisor(DepsDevClient depsDev, OsvClient osv, JarFetcher jars, FileCache cache) {
        this.depsDev = depsDev;
        this.osv = osv;
        this.jars = jars;
        this.cache = cache;
    }

    public static UpgradeAdvisor create(Path cacheDir, URI depsDevBaseUrl, URI osvBaseUrl, URI mavenCentral) {
        return new UpgradeAdvisor(DepsDevClient.create(depsDevBaseUrl), OsvClient.create(osvBaseUrl),
                new JarFetcher(mavenCentral, cacheDir.resolve("tmp-jars")), new FileCache(cacheDir, Clock.systemUTC()));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Stage stage() {
        return Stage.ADVISOR;
    }

    @Override
    public AnalyzerResult analyze(ScanContext ctx) throws IOException {
        LookupPrivacy privacy = LookupPrivacy.of(ctx.config().privacy(), ctx.project().coordinate());
        boolean offline = ctx.config().offline();
        int java = ctx.project().javaRelease();
        Map<String, Dependency> byGav = new LinkedHashMap<>();
        ctx.graph().dependencies().forEach(d -> byGav.putIfAbsent(gav(d.coordinate()), d));

        List<Finding> findings = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        for (DependencyVulnerabilities v : ctx.resultsOf(DependencyVulnerabilities.class)) {
            if (v.advisories().isEmpty()) continue;
            Dependency dep = byGav.get(gav(v.dependency()));
            if (dep == null) continue;
            UpgradeSuggestion s = suggest(dep, v, java, privacy, offline);
            data.add(s);
            findings.add(finding(dep, v, s, java));
        }
        return new AnalyzerResult(findings, data);
    }

    private UpgradeSuggestion suggest(Dependency dep, DependencyVulnerabilities v, int java, LookupPrivacy privacy,
                                      boolean offline) throws IOException {
        Coordinate c = new Coordinate(dep.coordinate().groupId(), dep.coordinate().artifactId(), dep.coordinate().version());
        Optional<Coordinate> via = dep.direct() || dep.path().size() < 3 ? Optional.empty() : Optional.of(dep.path().get(1));
        int fixes = v.advisories().size();
        Optional<String> reason = privacy.reasonPrivate(c);
        if (reason.isPresent()) return none(c, via, fixes, "not looked up: " + reason.get());

        Optional<List<String>> versions = cached("upgrade-versions", c.ga(), DAY, STRINGS);
        if (versions.isEmpty()) {
            if (offline) return none(c, via, fixes, "offline and no cached version list");
            versions = Optional.of(depsDev.versions(c.ga()).orElse(List.of()));
            cache.put("upgrade-versions", c.ga(), versions.get());
        }
        ComparableVersion current = new ComparableVersion(c.version());
        List<String> candidates = versions.get().stream()
                .filter(x -> !PRE_RELEASE.matcher(x).matches())
                .filter(x -> new ComparableVersion(x).compareTo(current) > 0)
                .sorted(Comparator.comparing(ComparableVersion::new)).limit(WINDOW).toList();
        if (candidates.isEmpty()) return none(c, via, fixes, "no newer stable version published");

        List<String> clean = cleanCandidates(c, candidates, offline);
        if (clean == null) return none(c, via, fixes, "offline and candidate versions not in the cache");
        if (clean.isEmpty()) return none(c, via, fixes, "every one of the next " + candidates.size()
                + " stable versions still has known vulnerabilities (up to " + candidates.get(candidates.size() - 1) + ")");

        String firstTooNew = null;
        int firstTooNewJava = 0;
        for (String candidate : clean.subList(0, Math.min(MAX_JAR_CHECKS, clean.size()))) {
            OptionalInt required = requiredJava(new Coordinate(c.groupId(), c.artifactId(), candidate), offline);
            if (required.isEmpty() || required.getAsInt() <= java) {
                return new UpgradeSuggestion(c, Optional.of(candidate),
                        required.isPresent() ? Optional.of(required.getAsInt()) : Optional.empty(),
                        major(candidate).equals(major(c.version())), via, fixes,
                        required.isPresent() ? "" : "Java requirement unknown (no jar on Maven Central)");
            }
            if (firstTooNew == null) { firstTooNew = candidate; firstTooNewJava = required.getAsInt(); }
        }
        return new UpgradeSuggestion(c, Optional.empty(), Optional.of(firstTooNewJava), false, via, fixes,
                "lowest fixed version " + firstTooNew + " needs Java " + firstTooNewJava + "; project targets Java " + java);
    }

    /** Candidates without known vulnerabilities (OSV, batch); null if offline and anything is uncached. */
    private List<String> cleanCandidates(Coordinate c, List<String> candidates, boolean offline) throws IOException {
        Map<String, List<String>> ids = new LinkedHashMap<>();
        List<Coordinate> toQuery = new ArrayList<>();
        for (String cand : candidates) {
            String key = c.ga() + ":" + cand;
            Optional<List<String>> hit = cached("osv-query", key, DAY, STRINGS);
            if (hit.isPresent()) ids.put(cand, hit.get());
            else toQuery.add(new Coordinate(c.groupId(), c.artifactId(), cand));
        }
        if (!toQuery.isEmpty()) {
            if (offline) return null;
            List<List<String>> answers = osv.query(toQuery);
            for (int i = 0; i < toQuery.size(); i++) {
                ids.put(toQuery.get(i).version(), answers.get(i));
                cache.put("osv-query", c.ga() + ":" + toQuery.get(i).version(), answers.get(i));
            }
        }
        return candidates.stream().filter(cand -> ids.getOrDefault(cand, List.of()).isEmpty()).toList();
    }

    private OptionalInt requiredJava(Coordinate c, boolean offline) throws IOException {
        Optional<Integer> hit = cached("javaversion", gav(c), FOREVER, INT);
        if (hit.isPresent()) return hit.get() < 0 ? OptionalInt.empty() : OptionalInt.of(hit.get());
        if (offline) return OptionalInt.empty();
        Optional<Path> jar = jars.fetch(c);
        int release = -1;
        if (jar.isPresent()) {
            try {
                release = ClassFileVersion.scanJar(jar.get()).requiredRelease().orElse(-1);
            } finally {
                Files.deleteIfExists(jar.get());
            }
        }
        cache.put("javaversion", gav(c), release);
        return release < 0 ? OptionalInt.empty() : OptionalInt.of(release);
    }

    private static Finding finding(Dependency dep, DependencyVulnerabilities v, UpgradeSuggestion s, int java) {
        Coordinate c = dep.coordinate();
        String n = s.fixes() + (s.fixes() == 1 ? " advisory" : " advisories");
        Severity severity;
        String title;
        if (s.recommended().isPresent()) {
            severity = Severity.INFO;
            title = "Upgrade " + c.ga() + " " + c.version() + " -> " + s.recommended().get() + " (fixes " + n
                    + s.requiredJavaRelease().map(j -> "; runs on Java " + j + "+").orElse("") + ")";
        } else {
            severity = s.requiredJavaRelease().isPresent() ? Severity.MEDIUM : Severity.LOW;
            title = "No safe upgrade for " + c.ga() + " " + c.version() + " on Java " + java
                    + (s.requiredJavaRelease().isPresent() ? ": the fix needs Java " + s.requiredJavaRelease().get() : "");
        }
        StringBuilder detail = new StringBuilder();
        s.viaDirect().ifPresent(d -> detail.append("Pulled in by ").append(d).append(": upgrade that dependency, or "
                + "override the version (Maven <dependencyManagement>, Gradle constraints). "));
        if (!s.note().isEmpty()) detail.append(s.note()).append(". ");
        if (!s.sameMajor() && s.recommended().isPresent()) detail.append("Major version change: check release notes. ");
        Map<String, String> evidence = new LinkedHashMap<>();
        s.recommended().ifPresent(r -> evidence.put("recommended", r));
        s.requiredJavaRelease().ifPresent(j -> evidence.put("requiredJavaRelease", String.valueOf(j)));
        s.viaDirect().ifPresent(d -> evidence.put("viaDirect", d.toString()));
        evidence.put("fixes", v.advisories().stream().map(Advisory::id).reduce((a, b) -> a + "," + b).orElse(""));
        return new Finding(FindingType.UPGRADE + ":" + c.ga(), FindingType.UPGRADE, severity, title,
                detail.toString().trim(), Optional.of(c), Optional.empty(), evidence);
    }

    private <T> Optional<T> cached(String ns, String key, Duration ttl, TypeReference<T> type) {
        return cache.get(ns, key, ttl, type);
    }

    private static UpgradeSuggestion none(Coordinate c, Optional<Coordinate> via, int fixes, String note) {
        return new UpgradeSuggestion(c, Optional.empty(), Optional.empty(), false, via, fixes, note);
    }

    private static String major(String version) {
        return version.split("[.-]")[0].toLowerCase(Locale.ROOT);
    }

    private static String gav(Coordinate c) {
        return c.ga() + ":" + c.version();
    }
}
