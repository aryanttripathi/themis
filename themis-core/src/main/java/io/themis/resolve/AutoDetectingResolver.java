package io.themis.resolve;

import io.themis.config.ScanConfig;

import java.nio.file.Path;
import java.util.List;

/** Delegates to the first resolver that understands the project (Maven before Gradle). */
public final class AutoDetectingResolver implements DependencyResolver {

    private final List<DependencyResolver> resolvers;

    public AutoDetectingResolver(List<DependencyResolver> resolvers) {
        this.resolvers = List.copyOf(resolvers);
    }

    @Override
    public boolean supports(Path projectDir) {
        return resolvers.stream().anyMatch(r -> r.supports(projectDir));
    }

    @Override
    public ProjectSession open(Path projectDir, ScanConfig config) {
        return resolvers.stream().filter(r -> r.supports(projectDir)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No pom.xml or Gradle build (settings.gradle[.kts] / "
                        + "build.gradle[.kts]) found in " + projectDir.toAbsolutePath().normalize()))
                .open(projectDir, config);
    }
}
