package io.themis.resolve.maven;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;
import io.themis.model.Scope;
import io.themis.resolve.DeclaredLicense;
import io.themis.resolve.PomLicenses;
import io.themis.resolve.ProjectSession;
import io.themis.resolve.ResolutionException;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingResult;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.util.artifact.JavaScopes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One scan of one Maven build: a single {@link MavenRuntime}, the reactor (root + modules) with
 * their effective models built once, and dependency resolution over those models.
 * <p>
 * Reactor loading mirrors Maven's two-phase project building: phase 1 for every module first
 * (so coordinates such as {@code ${revision}} are final), then the reactor workspace is installed,
 * then phase 2 (so a BOM that is itself a module can be imported, by the root or a sibling).
 */
final class MavenProjectSession implements ProjectSession {

    /** A project of the build (root or module) with its final effective model. */
    private record Module(Path pom, Coordinate coordinate, Model model) {
    }

    private record Pending(Path pom, Coordinate coordinate, MavenRuntime.PartialModel partial) {
    }

    private final MavenRuntime runtime;
    private final ScanConfig config;
    private final List<Module> modules;
    private final ProjectInfo project;
    /** g:a:v -> licenses of that POM; dependency POMs are read at most once per scan. */
    private final Map<String, PomLicenses> pomLicenses = new java.util.HashMap<>();
    private List<RemoteRepository> allRepositories;

    private MavenProjectSession(MavenRuntime runtime, ScanConfig config, List<Module> modules, ProjectInfo project) {
        this.runtime = runtime;
        this.config = config;
        this.modules = modules;
        this.project = project;
    }

    static MavenProjectSession open(ResolverOptions options, Path projectDir, ScanConfig config) {
        Path root = projectDir.toAbsolutePath().normalize();
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            throw new IllegalArgumentException("No pom.xml found in " + root);
        }
        MavenRuntime runtime = MavenRuntime.open(options, config.offline());
        try {
            List<Module> modules = loadReactor(runtime, pom);
            ProjectInfo info = MavenProjectInfo.from(root, modules.get(0).model(), config);
            return new MavenProjectSession(runtime, config, modules, info);
        } catch (RuntimeException e) {
            runtime.close();
            throw e;
        }
    }

    @Override
    public ProjectInfo project() {
        return project;
    }

    /**
     * Resolves every module and merges them: one entry per artifact <em>and version</em>
     * ({@code g:a:extension:classifier:version}), recording which modules reach it and the widest
     * scope among them.
     */
    @Override
    public DependencyGraph resolveDependencies() {
        Set<String> scopes = new LinkedHashSet<>(List.of(
                JavaScopes.COMPILE, JavaScopes.RUNTIME, JavaScopes.PROVIDED, JavaScopes.SYSTEM));
        if (config.includeTestScope()) scopes.add(JavaScopes.TEST);
        Set<String> reactorGas = new LinkedHashSet<>();
        modules.forEach(m -> reactorGas.add(m.coordinate().ga()));

        Map<String, Dependency> merged = new LinkedHashMap<>();
        for (Module module : modules) {
            for (Dependency d : resolveModule(module, scopes, reactorGas)) {
                merged.merge(d.coordinate().artifactKey() + ":" + d.coordinate().version(), d, MavenProjectSession::merge);
            }
        }
        return new DependencyGraph(project.coordinate(), List.copyOf(merged.values()), runtime.warnings());
    }

    /**
     * {@code <licenses>} of the dependency's effective POM (parents applied by the model builder).
     * A classified artifact ({@code linux-x86_64}, {@code tests}) shares its main artifact's POM.
     * Synchronized: analyzers may run concurrently; the work is local (POMs were fetched during resolution).
     */
    @Override
    public synchronized PomLicenses pomLicenses(Coordinate dependency) {
        String key = dependency.groupId() + ":" + dependency.artifactId() + ":" + dependency.version();
        return pomLicenses.computeIfAbsent(key, k -> readPomLicenses(dependency));
    }

    private PomLicenses readPomLicenses(Coordinate c) {
        Artifact pom = new DefaultArtifact(c.groupId(), c.artifactId(), "", "pom", c.version());
        Path file;
        try {
            file = runtime.system().resolveArtifact(runtime.session(),
                    new ArtifactRequest(pom, allRepositories(), "project")).getArtifact().getFile().toPath();
        } catch (ArtifactResolutionException e) {
            return PomLicenses.unreadable("Cannot resolve the POM " + pom + ": " + e.getMessage());
        }
        ModelBuildingResult result;
        try {
            result = runtime.buildRepositoryModel(file, allRepositories());
        } catch (ResolutionException e) {
            return PomLicenses.unreadable(e.getMessage());
        }
        List<DeclaredLicense> declared = result.getEffectiveModel().getLicenses().stream()
                .map(l -> new DeclaredLicense(l.getName(), l.getUrl())).toList();
        List<License> own = result.getRawModel().getLicenses();
        return new PomLicenses(declared, own.isEmpty() && !declared.isEmpty(), Optional.empty());
    }

    /** Union of the modules' repositories (with mirrors etc. applied), in reactor order. */
    private List<RemoteRepository> allRepositories() {
        if (allRepositories == null) {
            Map<String, RemoteRepository> byId = new LinkedHashMap<>();
            for (Module m : modules) {
                runtime.remoteRepositories(m.model()).forEach(r -> byId.putIfAbsent(r.getId(), r));
            }
            allRepositories = List.copyOf(byId.values());
        }
        return allRepositories;
    }

    @Override
    public void close() {
        runtime.close();
    }

    // --- reactor -------------------------------------------------------------------------------

    private static List<Module> loadReactor(MavenRuntime runtime, Path rootPom) {
        List<Pending> pending = new ArrayList<>();
        discover(runtime, rootPom, pending, new LinkedHashSet<>());

        Map<Coordinate, Path> poms = new LinkedHashMap<>();
        pending.forEach(p -> poms.put(p.coordinate(), p.pom()));
        runtime.useWorkspace(new ReactorWorkspace(poms));

        List<Module> modules = new ArrayList<>();
        for (Pending p : pending) {
            modules.add(new Module(p.pom(), p.coordinate(), runtime.buildPhase2(p.pom(), p.partial())));
        }
        return List.copyOf(modules);
    }

    /** Phase 1 of the POM and, recursively, of its {@code <modules>} (incl. those of active profiles). */
    private static void discover(MavenRuntime runtime, Path pom, List<Pending> out, Set<Path> seen) {
        if (!seen.add(pom)) return;
        MavenRuntime.PartialModel partial = runtime.buildPhase1(pom);
        Model model = partial.model();
        Coordinate coordinate = new Coordinate(model.getGroupId(), model.getArtifactId(), model.getVersion());
        if (coordinate.toString().contains("${")) {
            // Would otherwise be looked up in remote repositories under a bogus version.
            throw new ResolutionException("Cannot determine the coordinate of " + pom + ": " + coordinate
                    + " still contains an expression; define the property in the root POM's <properties> "
                    + "or pass it as a system property (e.g. java -Drevision=1.0 -jar themis.jar ...)",
                    List.of(), null);
        }
        out.add(new Pending(pom, coordinate, partial));

        for (String module : model.getModules()) {
            Path modulePath = pom.getParent().resolve(module).normalize();
            Path modulePom = Files.isDirectory(modulePath) ? modulePath.resolve("pom.xml") : modulePath;
            if (!Files.isRegularFile(modulePom)) {
                throw new IllegalArgumentException("Module '" + module + "' of " + pom + " has no pom.xml at " + modulePom);
            }
            discover(runtime, modulePom, out, seen);
        }
    }

    // --- resolution ----------------------------------------------------------------------------

    private List<Dependency> resolveModule(Module module, Set<String> scopes, Set<String> reactorGas) {
        RepositorySystemSession session = runtime.session();
        Model model = module.model();
        Coordinate self = module.coordinate();

        CollectRequest collect = new CollectRequest();
        collect.setRootArtifact(new DefaultArtifact(self.groupId(), self.artifactId(), "", "pom", self.version()));
        model.getDependencies().forEach(d -> collect.addDependency(toAether(d, session)));
        if (model.getDependencyManagement() != null) {
            // Already contains entries imported from BOMs: the model builder inlines them in phase 2.
            model.getDependencyManagement().getDependencies()
                    .forEach(d -> collect.addManagedDependency(toAether(d, session)));
        }
        collect.setRepositories(runtime.remoteRepositories(model));
        collect.setRequestContext("project");

        // Siblings are walked through (their POM comes from the workspace) but never resolved as
        // artifacts: their jars are not built and they are not third-party code.
        DependencyFilter filter = (node, parents) -> node.getDependency() == null
                || (scopes.contains(node.getDependency().getScope()) && !reactorGas.contains(ga(node.getArtifact())));

        DependencyResult result;
        try {
            result = runtime.system().resolveDependencies(session, new DependencyRequest(collect, filter));
        } catch (DependencyResolutionException e) {
            throw failure(self, e);
        }

        Map<String, Dependency> out = new LinkedHashMap<>();
        walk(result.getRoot(), List.of(self), self, scopes, reactorGas, Collections.newSetFromMap(new IdentityHashMap<>()), out);
        return List.copyOf(out.values());
    }

    /**
     * Depth-first, pre-order (like {@code mvn dependency:tree}). After Maven Resolver's conflict
     * resolution the tree holds only mediation winners, so each {@code g:a:ext:classifier} appears
     * once; the guard on the current path protects against cycles.
     */
    private static void walk(DependencyNode node, List<Coordinate> path, Coordinate module, Set<String> scopes,
                             Set<String> reactorGas, Set<DependencyNode> onPath, Map<String, Dependency> out) {
        for (DependencyNode child : node.getChildren()) {
            org.eclipse.aether.graph.Dependency dep = child.getDependency();
            if (dep == null || !scopes.contains(dep.getScope()) || !onPath.add(child)) continue;

            Artifact a = child.getArtifact();
            Coordinate coordinate = new Coordinate(a.getGroupId(), a.getArtifactId(), a.getBaseVersion(),
                    a.getExtension(), a.getClassifier());
            List<Coordinate> childPath = new ArrayList<>(path);
            childPath.add(coordinate);

            if (!reactorGas.contains(coordinate.ga())) {
                out.putIfAbsent(coordinate.artifactKey(), new Dependency(coordinate, Scope.fromMaven(dep.getScope()),
                        path.size() == 1, childPath, jarFile(a), List.of(module)));
            }
            walk(child, childPath, module, scopes, reactorGas, onPath, out);
            onPath.remove(child);
        }
    }

    /**
     * The same artifact and version reached by several modules: widest scope, direct if direct
     * anywhere, all modules in reactor order, and the most direct (then shortest) path as the example.
     */
    private static Dependency merge(Dependency existing, Dependency candidate) {
        boolean preferCandidate = candidate.direct() != existing.direct()
                ? candidate.direct()
                : candidate.path().size() < existing.path().size();
        Dependency example = preferCandidate ? candidate : existing;
        LinkedHashSet<Coordinate> modules = new LinkedHashSet<>(existing.modules());
        modules.addAll(candidate.modules());
        return new Dependency(existing.coordinate(), Scope.widest(existing.scope(), candidate.scope()),
                existing.direct() || candidate.direct(), example.path(),
                existing.jarFile().or(candidate::jarFile), List.copyOf(modules));
    }

    /** Like Maven's {@code RepositoryUtils.toDependency}: type stereotypes, system paths, exclusions. */
    private static org.eclipse.aether.graph.Dependency toAether(org.apache.maven.model.Dependency d,
                                                                RepositorySystemSession session) {
        ArtifactType type = Optional.ofNullable(session.getArtifactTypeRegistry().get(d.getType()))
                .orElseGet(() -> new DefaultArtifactType(d.getType()));
        Map<String, String> props = d.getSystemPath() == null ? null
                : Map.of(ArtifactProperties.LOCAL_PATH, d.getSystemPath());
        Artifact artifact = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(),
                type.getExtension(), d.getVersion(), props, type);
        List<Exclusion> exclusions = d.getExclusions().stream()
                .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*"))
                .toList();
        return new org.eclipse.aether.graph.Dependency(artifact, d.getScope(), d.isOptional(), exclusions);
    }

    private static ResolutionException failure(Coordinate project, DependencyResolutionException e) {
        Set<String> unresolved = new LinkedHashSet<>();
        DependencyResult result = e.getResult();
        if (result != null) {
            for (Exception ce : result.getCollectExceptions()) {
                if (ce instanceof DependencyCollectionException dce) {
                    for (Exception inner : dce.getResult().getExceptions()) {
                        if (inner instanceof ArtifactDescriptorException ade) {
                            unresolved.add(ade.getResult().getArtifact().toString());
                        }
                    }
                }
            }
            for (ArtifactResult ar : result.getArtifactResults()) {
                if (!ar.isResolved()) unresolved.add(ar.getRequest().getArtifact().toString());
            }
        }
        String what = unresolved.isEmpty() ? "" : " unresolvable artifacts " + unresolved + ";";
        return new ResolutionException("Cannot resolve dependencies of " + project + ":" + what + " " + causeChain(e),
                List.copyOf(unresolved), e);
    }

    /** The exception's message followed by each distinct cause message, like mvn's "Caused by" lines. */
    private static String causeChain(Throwable e) {
        Set<String> messages = new LinkedHashSet<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && messages.stream().noneMatch(m -> m.contains(message))) {
                messages.add(message);
            }
        }
        return String.join(": ", messages);
    }

    /** Only actual jars: a {@code type=pom} dependency resolves to a POM, which is not a jar. */
    private static Optional<Path> jarFile(Artifact artifact) {
        return artifact.getFile() != null && "jar".equals(artifact.getExtension())
                ? Optional.of(artifact.getFile().toPath())
                : Optional.empty();
    }

    private static String ga(Artifact a) {
        return a.getGroupId() + ":" + a.getArtifactId();
    }
}
