package io.themis.resolve.gradle;

import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;
import io.themis.model.Scope;
import io.themis.resolve.DeclaredLicense;
import io.themis.resolve.JavaRelease;
import io.themis.resolve.PomLicenses;
import io.themis.resolve.ProjectSession;
import io.themis.resolve.ResolutionException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A resolved Gradle build. Gradle configurations map to scopes per project:
 * <ul>
 *   <li>compileClasspath and runtimeClasspath: {@code COMPILE}</li>
 *   <li>runtimeClasspath only ({@code runtimeOnly}): {@code RUNTIME}</li>
 *   <li>compileClasspath only ({@code compileOnly}, e.g. Lombok): {@code PROVIDED}, not shipped</li>
 *   <li>testRuntimeClasspath only: {@code TEST} (with {@code --include-test})</li>
 * </ul>
 * Subprojects are merged like Maven modules: one entry per {@code g:a:v}, widest scope, the
 * subprojects that use it in {@code modules}; sibling projects never appear as dependencies.
 */
final class GradleProjectSession implements ProjectSession {

    private static final String COMPILE = "compileClasspath";
    private static final String RUNTIME = "runtimeClasspath";
    private static final String TEST = "testRuntimeClasspath";

    private final ProjectInfo project;
    private final DependencyGraph graph;
    private final Map<String, PomLicenses> licenses = new LinkedHashMap<>();

    GradleProjectSession(Path root, GradleDump dump, boolean includeTest) {
        failOnUnresolved(dump, includeTest);
        Coordinate rootCoordinate = coordinate(dump.root().gav());
        this.project = projectInfo(root, rootCoordinate, dump);
        this.graph = new DependencyGraph(rootCoordinate, merge(dump, includeTest), dump.warnings());
        dump.licenses().forEach((gav, pom) -> licenses.put(gav, new PomLicenses(
                pom.licenses().stream().map(l -> new DeclaredLicense(l.name(), l.url())).toList(),
                pom.inherited(), Optional.ofNullable(pom.problem()))));
    }

    @Override
    public ProjectInfo project() {
        return project;
    }

    @Override
    public DependencyGraph resolveDependencies() {
        return graph;
    }

    @Override
    public PomLicenses pomLicenses(Coordinate dependency) {
        return licenses.getOrDefault(dependency.toString(), PomLicenses.NONE);
    }

    @Override
    public void close() {
        // nothing held open: Gradle already exited
    }

    private static void failOnUnresolved(GradleDump dump, boolean includeTest) {
        List<String> missing = dump.unresolved().stream()
                .filter(u -> includeTest || !TEST.equals(u.configuration()))
                .map(GradleDump.Unresolved::requested).distinct().toList();
        if (missing.isEmpty()) return;
        String reasons = dump.unresolved().stream().map(u -> u.project() + " " + u.configuration() + ": " + u.reason())
                .distinct().limit(5).reduce((a, b) -> a + "; " + b).orElse("");
        throw new ResolutionException("Cannot resolve dependencies of " + dump.root().gav() + ": unresolvable artifacts "
                + missing + "; " + reasons, missing, null);
    }

    /** Strictest (lowest) Java release among the Java projects; 17 if none declares one. */
    private static ProjectInfo projectInfo(Path root, Coordinate coordinate, GradleDump dump) {
        int release = dump.projects().stream().map(GradleDump.Project::javaRelease).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).min().orElse(JavaRelease.DEFAULT);
        List<Path> sources = existing(dump.projects().stream().flatMap(p -> p.sourceDirs().stream()).toList());
        List<Path> resources = existing(dump.projects().stream().flatMap(p -> p.resourceDirs().stream()).toList());
        return new ProjectInfo(root, coordinate, release, sources, resources);
    }

    private static List<Dependency> merge(GradleDump dump, boolean includeTest) {
        Map<String, Dependency> merged = new LinkedHashMap<>();
        for (GradleDump.Project p : dump.projects()) {
            Coordinate module = coordinate(p.coordinate().gav());
            Map<String, GradleDump.Node> compile = index(p.configurations().get(COMPILE));
            Map<String, GradleDump.Node> runtime = index(p.configurations().get(RUNTIME));
            Map<String, GradleDump.Node> test = includeTest ? index(p.configurations().get(TEST)) : Map.of();

            Set<String> all = new LinkedHashSet<>(runtime.keySet());
            all.addAll(compile.keySet());
            all.addAll(test.keySet());
            for (String gav : all) {
                Scope scope;
                if (runtime.containsKey(gav)) scope = compile.containsKey(gav) ? Scope.COMPILE : Scope.RUNTIME;
                else if (compile.containsKey(gav)) scope = Scope.PROVIDED;
                else scope = Scope.TEST;
                GradleDump.Node node = runtime.getOrDefault(gav, compile.getOrDefault(gav, test.get(gav)));
                Dependency d = new Dependency(coordinate(gav), scope, node.direct(),
                        node.path().stream().map(GradleProjectSession::coordinate).toList(), jar(node), List.of(module));
                merged.merge(gav, d, GradleProjectSession::combine);
            }
        }
        List<Dependency> out = new ArrayList<>(merged.values());
        out.sort(Comparator.comparing((Dependency d) -> !d.direct()).thenComparing(d -> d.coordinate().toString()));
        return out;
    }

    private static Dependency combine(Dependency a, Dependency b) {
        List<Coordinate> modules = new ArrayList<>(a.modules());
        b.modules().stream().filter(m -> !modules.contains(m)).forEach(modules::add);
        Dependency best = (b.direct() && !a.direct()) || (b.direct() == a.direct() && b.path().size() < a.path().size()) ? b : a;
        return new Dependency(best.coordinate(), Scope.widest(a.scope(), b.scope()), a.direct() || b.direct(),
                best.path(), a.jarFile().or(b::jarFile), modules);
    }

    private static Map<String, GradleDump.Node> index(List<GradleDump.Node> nodes) {
        Map<String, GradleDump.Node> m = new LinkedHashMap<>();
        if (nodes != null) nodes.forEach(n -> m.putIfAbsent(n.coordinate(), n));
        return m;
    }

    private static Optional<Path> jar(GradleDump.Node node) {
        return node.files().stream().filter(f -> f.endsWith(".jar")).map(Path::of).findFirst();
    }

    private static List<Path> existing(List<String> dirs) {
        return dirs.stream().map(Path::of).filter(Files::isDirectory).distinct().toList();
    }

    /** {@code g:a:v}; Gradle allows an empty group and "unspecified" versions. */
    private static Coordinate coordinate(String gav) {
        String[] p = gav.split(":", -1);
        return new Coordinate(p[0].isEmpty() ? "unspecified" : p[0], p[1], p[2].isEmpty() ? "unspecified" : p[2]);
    }
}
