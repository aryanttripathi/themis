package io.themis.resolve.maven;

import io.themis.model.Coordinate;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Makes the POMs of the modules of a multi-module build visible to the resolver, like Maven's
 * reactor: when module {@code app} depends on sibling {@code core}, core's descriptor (and so its
 * own dependencies) is read from {@code core/pom.xml} instead of a repository where it was never
 * deployed. Module jars are not offered (they are not built); the resolver filters siblings out
 * before artifact resolution.
 */
final class ReactorWorkspace implements WorkspaceReader {

    private static final WorkspaceRepository REPOSITORY = new WorkspaceRepository("reactor");

    private final Map<Coordinate, Path> poms;

    ReactorWorkspace(Map<Coordinate, Path> poms) {
        this.poms = Map.copyOf(poms);
    }

    @Override
    public WorkspaceRepository getRepository() {
        return REPOSITORY;
    }

    @Override
    public File findArtifact(Artifact artifact) {
        if (!"pom".equals(artifact.getExtension()) || !artifact.getClassifier().isEmpty()) {
            return null;
        }
        Path pom = poms.get(new Coordinate(artifact.getGroupId(), artifact.getArtifactId(), artifact.getBaseVersion()));
        return pom == null ? null : pom.toFile();
    }

    @Override
    public List<String> findVersions(Artifact artifact) {
        return poms.keySet().stream()
                .filter(c -> c.groupId().equals(artifact.getGroupId()) && c.artifactId().equals(artifact.getArtifactId()))
                .map(Coordinate::version)
                .toList();
    }
}
