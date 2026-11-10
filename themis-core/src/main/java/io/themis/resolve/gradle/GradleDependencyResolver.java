package io.themis.resolve.gradle;

import io.themis.config.ScanConfig;
import io.themis.resolve.DependencyResolver;
import io.themis.resolve.ProjectSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Resolves Gradle builds by running the project's own Gradle ({@code ./gradlew}) with Themis' init
 * script, which writes the resolved graph of every project as JSON (see {@code themis-init.gradle}).
 * Gradle's resolution (highest version wins, platforms, the Spring dependency-management plugin,
 * repositories, credentials) cannot be reproduced faithfully outside Gradle, so we don't try.
 *
 * <p>Trust: this executes the project's build scripts, like any Gradle build does.
 */
public final class GradleDependencyResolver implements DependencyResolver {

    static final List<String> BUILD_FILES = List.of("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts");

    private final GradleOptions options;

    public GradleDependencyResolver(GradleOptions options) {
        this.options = Objects.requireNonNull(options, "options");
    }

    /** A Gradle build without a pom.xml (projects with both are treated as Maven). */
    @Override
    public boolean supports(Path projectDir) {
        if (Files.isRegularFile(projectDir.resolve("pom.xml"))) return false;
        return BUILD_FILES.stream().anyMatch(f -> Files.isRegularFile(projectDir.resolve(f)));
    }

    @Override
    public ProjectSession open(Path projectDir, ScanConfig config) {
        Path root = projectDir.toAbsolutePath().normalize();
        GradleDump dump = new GradleRunner(options).dump(root, config.includeTestScope(), config.offline());
        return new GradleProjectSession(root, dump, config.includeTestScope());
    }
}
