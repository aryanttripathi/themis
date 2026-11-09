package io.themis.resolve.maven;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.ProjectInfo;
import io.themis.resolve.JavaRelease;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Maps an effective Maven model to {@link ProjectInfo}. */
final class MavenProjectInfo {

    private MavenProjectInfo() {
    }

    static ProjectInfo from(Path rootDir, Model model, ScanConfig config) {
        Coordinate coordinate = new Coordinate(
                Optional.ofNullable(model.getGroupId()).orElse("unknown"),
                Optional.ofNullable(model.getArtifactId()).orElse(rootDir.getFileName().toString()),
                Optional.ofNullable(model.getVersion()).orElse("0"));
        int javaRelease = config.javaRelease().isPresent()
                ? config.javaRelease().getAsInt()
                : JavaRelease.detect(model.getProperties());

        Build build = model.getBuild();
        List<String> sources = new ArrayList<>();
        List<String> resources = new ArrayList<>();
        if (build != null) {
            Optional.ofNullable(build.getSourceDirectory()).ifPresent(sources::add);
            build.getResources().stream().map(Resource::getDirectory).forEach(resources::add);
        }
        return new ProjectInfo(rootDir, coordinate, javaRelease, existing(rootDir, sources), existing(rootDir, resources));
    }

    /** Effective-model paths are absolute already; relative ones are taken against the project root. */
    private static List<Path> existing(Path root, List<String> dirs) {
        List<Path> out = new ArrayList<>();
        for (String d : dirs) {
            if (d == null) continue;
            Path p = root.resolve(d).toAbsolutePath().normalize();
            if (Files.isDirectory(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }
}
