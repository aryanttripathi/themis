package io.themis.resolve;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AutoDetectingResolverTest {

    @TempDir
    Path dir;

    @Test
    void picksTheFirstResolverThatSupportsTheProject() throws Exception {
        Files.writeString(dir.resolve("build.gradle"), "");
        AutoDetectingResolver auto = new AutoDetectingResolver(List.of(fake("maven", "pom.xml"), fake("gradle", "build.gradle")));

        try (ProjectSession s = auto.open(dir, ScanConfig.defaults())) {
            assertThat(s.project().coordinate().artifactId()).isEqualTo("gradle");
        }
        assertThat(auto.supports(dir)).isTrue();
    }

    @Test
    void noBuildFileIsAClearError() {
        AutoDetectingResolver auto = new AutoDetectingResolver(List.of(fake("maven", "pom.xml"), fake("gradle", "build.gradle")));

        assertThat(auto.supports(dir)).isFalse();
        assertThatThrownBy(() -> auto.open(dir, ScanConfig.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No pom.xml or Gradle build")
                .hasMessageContaining(dir.toString());
    }

    private static DependencyResolver fake(String name, String marker) {
        return new DependencyResolver() {
            public boolean supports(Path p) {
                return Files.exists(p.resolve(marker));
            }

            public ProjectSession open(Path p, ScanConfig c) {
                Coordinate coord = new Coordinate("t", name, "1");
                return new ProjectSession() {
                    public ProjectInfo project() {
                        return new ProjectInfo(p, coord, 17, List.of(), List.of());
                    }

                    public DependencyGraph resolveDependencies() {
                        return DependencyGraph.empty(coord);
                    }

                    public void close() {
                    }
                };
            }
        };
    }
}
