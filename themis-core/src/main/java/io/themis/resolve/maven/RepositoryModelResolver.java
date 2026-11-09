package io.themis.resolve.maven;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets {@code maven-model-builder} fetch parent POMs and imported BOMs through Maven Resolver,
 * i.e. from the local repository, the workspace (reactor) or remote repositories with the
 * session's mirrors, proxies and credentials applied. Repositories declared in POMs met along
 * the way (a parent's {@code <repositories>}) are added as the model builder discovers them.
 * <p>
 * This mirrors Maven's own (package-private) {@code DefaultModelResolver}. With
 * {@code ignoreDeclaredRepositories} the repositories met in POMs are not added at all, so parents
 * and BOMs come only from the initial (settings + Central) repositories.
 */
final class RepositoryModelResolver implements ModelResolver {

    private final RepositorySystem system;
    private final RepositorySystemSession session;
    private final List<RemoteRepository> repositories;
    private final boolean ignoreDeclaredRepositories;

    RepositoryModelResolver(RepositorySystem system, RepositorySystemSession session,
                            List<RemoteRepository> repositories, boolean ignoreDeclaredRepositories) {
        this.system = system;
        this.session = session;
        this.repositories = new ArrayList<>(repositories);
        this.ignoreDeclaredRepositories = ignoreDeclaredRepositories;
    }

    @Override
    public ModelSource resolveModel(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        Artifact pom = new DefaultArtifact(groupId, artifactId, "", "pom", version);
        try {
            Artifact resolved = system.resolveArtifact(session,
                    new ArtifactRequest(pom, List.copyOf(repositories), "project")).getArtifact();
            return new FileModelSource(resolved.getFile());
        } catch (ArtifactResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
        }
    }

    @Override
    public ModelSource resolveModel(Parent parent) throws UnresolvableModelException {
        String version = highestInRange(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
        parent.setVersion(version); // the model builder expects the concrete version back
        return resolveModel(parent.getGroupId(), parent.getArtifactId(), version);
    }

    @Override
    public ModelSource resolveModel(Dependency dependency) throws UnresolvableModelException {
        String version = highestInRange(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
        dependency.setVersion(version);
        return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), version);
    }

    @Override
    public void addRepository(Repository repository) {
        addRepository(repository, false);
    }

    @Override
    public void addRepository(Repository repository, boolean replace) {
        if (ignoreDeclaredRepositories) {
            return; // --ignore-pom-repositories: POM-declared repositories are never contacted
        }
        if (!replace && repositories.stream().anyMatch(r -> r.getId().equals(repository.getId()))) {
            return; // first declaration wins, as in Maven
        }
        repositories.removeIf(r -> r.getId().equals(repository.getId()));
        for (RemoteRepository r : system.newResolutionRepositories(session, List.of(Repositories.toRemote(repository)))) {
            // After mirroring several repositories can map to the same mirror: keep one.
            if (repositories.stream().noneMatch(existing -> existing.getId().equals(r.getId()))) {
                repositories.add(r);
            }
        }
    }

    @Override
    public ModelResolver newCopy() {
        return new RepositoryModelResolver(system, session, repositories, ignoreDeclaredRepositories);
    }

    /** A plain version is returned as is; a range such as {@code [1.0,2.0)} resolves to its highest match. */
    private String highestInRange(String groupId, String artifactId, String version)
            throws UnresolvableModelException {
        if (version == null || !(version.startsWith("[") || version.startsWith("("))) {
            return version;
        }
        VersionRangeRequest request = new VersionRangeRequest(
                new DefaultArtifact(groupId, artifactId, "", "pom", version), List.copyOf(repositories), "project");
        try {
            VersionRangeResult result = system.resolveVersionRange(session, request);
            if (result.getHighestVersion() == null) {
                throw new UnresolvableModelException("No versions of " + groupId + ":" + artifactId
                        + " match " + version, groupId, artifactId, version);
            }
            return result.getHighestVersion().toString();
        } catch (VersionRangeResolutionException e) {
            throw new UnresolvableModelException(e.getMessage(), groupId, artifactId, version, e);
        }
    }
}
