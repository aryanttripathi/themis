package io.themis.resolve.maven;

import io.themis.Themis;
import io.themis.resolve.ResolutionException;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelCache;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One configured Maven Resolver environment for one scan: the {@link RepositorySystem}, a session
 * built from settings.xml (local repository, mirrors, proxies, server credentials, offline flag),
 * and two-phase effective-model building that resolves parents and BOM imports through it.
 * Non-fatal problems (missing/invalid transitive POMs, undecryptable passwords) are collected as
 * {@link #warnings()}.
 * <p>
 * Security: Maven 3.8.1+'s {@code external:http:*} blocker mirror is always installed (after the
 * user's own mirrors, which therefore take precedence), and with
 * {@link ResolverOptions#ignorePomRepositories()} repositories declared in POMs are never used.
 * <p>
 * Not thread-safe; {@link #close()} it when done.
 */
public final class MavenRuntime implements AutoCloseable {

    /** Id, URL and pattern of the blocker mirror shipped in Maven 3.8.1+'s global settings. */
    static final String HTTP_BLOCKER_ID = "maven-default-http-blocker";
    private static final String HTTP_BLOCKER_URL = "http://0.0.0.0/";
    private static final String HTTP_BLOCKER_MIRROR_OF = "external:http:*";

    /** Transport defaults suited to an interactive CLI; any -D system property of the same name wins. */
    private static final Map<String, String> TRANSPORT_DEFAULTS = Map.of(
            ConfigurationProperties.CONNECT_TIMEOUT, "10000",
            ConfigurationProperties.REQUEST_TIMEOUT, "60000",
            "aether.connector.http.retryHandler.count", "2",
            "aether.connector.http.retryHandler.intervalMax", "20000");

    private final RepositorySystem system;
    private final DefaultRepositorySystemSession session;
    private final ModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
    private final ModelCache modelCache = new MapModelCache();
    private final Properties systemProperties;
    private final Settings settings;
    private final List<RemoteRepository> settingsRepositories;
    private final boolean ignorePomRepositories;
    private final Set<String> warnings = Collections.synchronizedSet(new LinkedHashSet<>());

    private MavenRuntime(ResolverOptions options, boolean offline) {
        this.systemProperties = systemProperties();
        SettingsLoader.Loaded loaded = SettingsLoader.load(options, systemProperties);
        this.settings = loaded.settings();
        this.warnings.addAll(loaded.warnings());
        this.ignorePomRepositories = options.ignorePomRepositories();
        this.settingsRepositories = SettingsProfiles.explicitlyActiveRepositories(settings).stream()
                .map(Repositories::toRemote).toList();

        RepositorySystem repositorySystem = new RepositorySystemSupplier().get();
        try {
            this.session = newSession(repositorySystem, options, offline);
        } catch (RuntimeException | Error e) {
            repositorySystem.shutdown(); // nothing else would release its resources
            throw e;
        }
        this.system = repositorySystem;
    }

    /** Opens a runtime; {@code offline} puts Maven Resolver in offline mode (local repository only). */
    public static MavenRuntime open(ResolverOptions options, boolean offline) {
        return new MavenRuntime(options, offline);
    }

    /** A model after phase 1 (parents, inheritance, interpolation: the coordinate is final), before imports. */
    record PartialModel(ModelBuildingRequest request, ModelBuildingResult result) {
        Model model() {
            return result.getEffectiveModel();
        }
    }

    /**
     * Phase 1 of effective-model building: parents (on disk via relativePath or from repositories),
     * inheritance, profile activation (incl. settings profiles), interpolation. Enough to know the
     * project's final coordinate (CI-friendly {@code ${revision}} versions are resolved) and modules.
     *
     * @throws IllegalArgumentException if the POM declares a DOCTYPE (XXE protection) or is not XML
     * @throws ResolutionException      if the model cannot be built (e.g. parent not found)
     */
    PartialModel buildPhase1(Path pom) {
        PomSafety.requireNoDoctype(pom);
        DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile(pom.toFile());
        request.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_0);
        request.setProcessPlugins(false);
        request.setTwoPhaseBuilding(true);
        request.setLocationTracking(false);
        request.setSystemProperties(systemProperties);
        request.setProfiles(SettingsProfiles.toModelProfiles(settings)); // fresh copies: the builder may mutate them
        request.setActiveProfileIds(settings.getActiveProfiles());
        request.setModelCache(modelCache);
        request.setModelResolver(new RepositoryModelResolver(system, session, initialRepositories(), ignorePomRepositories));
        try {
            return new PartialModel(request, modelBuilder.build(request));
        } catch (ModelBuildingException e) {
            throw failure(pom, e);
        }
    }

    /**
     * Phase 2: dependencyManagement imports (BOMs, possibly from the reactor workspace), management
     * injection and normalization. Call after {@link #useWorkspace} so sibling BOMs resolve.
     */
    Model buildPhase2(Path pom, PartialModel partial) {
        try {
            return modelBuilder.build(partial.request(), partial.result()).getEffectiveModel();
        } catch (ModelBuildingException e) {
            throw failure(pom, e);
        }
    }

    /**
     * The repositories to resolve a project's dependencies from, with mirrors, proxies and
     * credentials applied: those of its effective model (central from the super POM, the project's,
     * its parents' and active settings profiles' repositories), or, with
     * {@link ResolverOptions#ignorePomRepositories()}, only settings profile repositories and Central.
     */
    List<RemoteRepository> remoteRepositories(Model effectiveModel) {
        Map<String, RemoteRepository> byId = new LinkedHashMap<>();
        if (ignorePomRepositories) {
            settingsRepositories.forEach(r -> byId.putIfAbsent(r.getId(), r));
            byId.putIfAbsent(Repositories.CENTRAL.getId(), Repositories.CENTRAL);
        } else {
            effectiveModel.getRepositories().forEach(r -> byId.putIfAbsent(r.getId(), Repositories.toRemote(r)));
            settingsRepositories.forEach(r -> byId.putIfAbsent(r.getId(), r));
        }
        return system.newResolutionRepositories(session, new ArrayList<>(byId.values()));
    }

    /** Makes the modules of a multi-module build resolvable by coordinate (see {@link ReactorWorkspace}). */
    void useWorkspace(WorkspaceReader workspace) {
        session.setWorkspaceReader(workspace);
    }

    /** Non-fatal problems so far, in order of occurrence, without duplicates. */
    List<String> warnings() {
        synchronized (warnings) {
            return List.copyOf(warnings);
        }
    }

    RepositorySystem system() {
        return system;
    }

    RepositorySystemSession session() {
        return session;
    }

    @Override
    public void close() {
        system.shutdown();
    }

    private List<RemoteRepository> initialRepositories() {
        List<RemoteRepository> repos = new ArrayList<>(settingsRepositories);
        repos.add(Repositories.CENTRAL);
        return system.newResolutionRepositories(session, repos);
    }

    private static ResolutionException failure(Path pom, ModelBuildingException e) {
        List<String> unresolved = new ArrayList<>();
        for (ModelProblem p : e.getProblems()) {
            if (p.getException() instanceof UnresolvableModelException u) {
                unresolved.add(u.getGroupId() + ":" + u.getArtifactId() + ":pom:" + u.getVersion());
            }
        }
        return new ResolutionException("Cannot build the effective POM of " + pom + ": " + e.getMessage(), unresolved, e);
    }

    private DefaultRepositorySystemSession newSession(RepositorySystem system, ResolverOptions options, boolean offline) {
        // Maven's defaults: scope/optional/exclusion selectors, nearest-wins conflict resolution,
        // and a lenient policy for missing/invalid transitive POMs (reported via the listener below).
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setOffline(offline);
        session.setSystemProperties(systemProperties);
        Map<String, Object> config = new HashMap<>(TRANSPORT_DEFAULTS);
        config.put(ConfigurationProperties.USER_AGENT, "Themis/" + Themis.VERSION);
        systemProperties.forEach((k, v) -> config.put(String.valueOf(k), v));
        session.setConfigProperties(config);
        session.setIgnoreArtifactDescriptorRepositories(options.ignorePomRepositories());
        session.setRepositoryListener(new WarningListener(warnings));

        Path localRepo = options.localRepository()
                .or(() -> Optional.ofNullable(settings.getLocalRepository()).filter(s -> !s.isBlank()).map(Path::of))
                .orElse(Path.of(System.getProperty("user.home"), ".m2", "repository"));
        session.setLocalRepositoryManager(
                system.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));

        DefaultMirrorSelector mirrors = new DefaultMirrorSelector();
        for (Mirror m : settings.getMirrors()) {
            mirrors.add(m.getId(), m.getUrl(), m.getLayout(), false, m.isBlocked(), m.getMirrorOf(), m.getMirrorOfLayouts());
        }
        if (settings.getMirrors().stream().noneMatch(m -> HTTP_BLOCKER_ID.equals(m.getId()))) {
            // Last, so a user's mirror for the same repository (or "*") is chosen first.
            mirrors.add(HTTP_BLOCKER_ID, HTTP_BLOCKER_URL, "default", false, true, HTTP_BLOCKER_MIRROR_OF, null);
        }
        session.setMirrorSelector(mirrors);

        DefaultProxySelector proxies = new DefaultProxySelector();
        for (org.apache.maven.settings.Proxy p : settings.getProxies()) {
            if (!p.isActive()) continue;
            Authentication auth = p.getUsername() == null ? null
                    : new AuthenticationBuilder().addUsername(p.getUsername()).addPassword(p.getPassword()).build();
            proxies.add(new Proxy(p.getProtocol(), p.getHost(), p.getPort(), auth), p.getNonProxyHosts());
        }
        session.setProxySelector(proxies);

        DefaultAuthenticationSelector credentials = new DefaultAuthenticationSelector();
        for (Server s : settings.getServers()) {
            credentials.add(s.getId(), new AuthenticationBuilder()
                    .addUsername(s.getUsername()).addPassword(s.getPassword())
                    .addPrivateKey(s.getPrivateKey(), s.getPassphrase())
                    .build());
        }
        session.setAuthenticationSelector(credentials);
        return session;
    }

    /** What Maven exposes to POM interpolation and profile activation: JVM properties plus {@code env.*}. */
    private static Properties systemProperties() {
        Properties props = new Properties();
        System.getenv().forEach((k, v) -> props.setProperty("env." + k, v));
        props.putAll(System.getProperties());
        return props;
    }

    /** Turns lenient-policy descriptor problems into Maven-worded warnings. */
    private static final class WarningListener extends AbstractRepositoryListener {
        private final Set<String> sink;

        WarningListener(Set<String> sink) {
            this.sink = sink;
        }

        @Override
        public void artifactDescriptorMissing(RepositoryEvent event) {
            sink.add("The POM for " + event.getArtifact() + " is missing, no dependency information available");
        }

        @Override
        public void artifactDescriptorInvalid(RepositoryEvent event) {
            String detail = event.getException() == null ? "" : ": " + event.getException().getMessage();
            sink.add("The POM for " + event.getArtifact()
                    + " is invalid, transitive dependencies (if any) will not be available" + detail);
        }
    }

    /** Per-runtime cache of raw/imported models: parents and BOMs shared by modules are read once. */
    private static final class MapModelCache implements ModelCache {
        private final Map<List<String>, Object> entries = new ConcurrentHashMap<>();

        @Override
        public void put(String groupId, String artifactId, String version, String tag, Object data) {
            entries.put(List.of(groupId, artifactId, version, tag), data);
        }

        @Override
        public Object get(String groupId, String artifactId, String version, String tag) {
            return entries.get(List.of(groupId, artifactId, version, tag));
        }
    }
}
