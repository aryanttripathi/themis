package io.themis.resolve.maven;

import io.themis.config.ScanConfig;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolves a tiny real project through Maven Central using the user's real ~/.m2/settings.xml
 * (proxies, mirrors, local repository). Excluded from the default build; run with
 * {@code mvn -pl themis-core -Pnetwork test -Dtest=MavenCentralNetworkTest} or {@code -Dgroups=network}.
 */
@Tag("network")
class MavenCentralNetworkTest {

    @TempDir
    Path project;

    @Test
    void resolvesCommonsTextAndItsTransitiveCommonsLang3FromCentral() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>io.themis.it</groupId>
                  <artifactId>network-it</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.commons</groupId>
                      <artifactId>commons-text</artifactId>
                      <version>1.12.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        ResolverOptions options = ResolverOptions.defaults();

        DependencyGraph graph = MavenDependencyResolverTest.resolve(options, project, ScanConfig.defaults());

        assertThat(graph.dependencies()).extracting(d -> d.coordinate().toString())
                .contains("org.apache.commons:commons-text:1.12.0", "org.apache.commons:commons-lang3:3.14.0");
        Dependency lang3 = graph.dependencies().stream()
                .filter(d -> d.coordinate().artifactId().equals("commons-lang3")).findFirst().orElseThrow();
        assertThat(lang3.direct()).isFalse();
        assertThat(lang3.jarFile()).hasValueSatisfying(jar -> assertThat(jar).exists());
    }
}
