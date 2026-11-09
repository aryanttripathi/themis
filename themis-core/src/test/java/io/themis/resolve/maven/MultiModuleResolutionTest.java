package io.themis.resolve.maven;

import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;
import io.themis.model.Scope;
import io.themis.resolve.ProjectSession;
import io.themis.resolve.ResolutionException;
import io.themis.testing.FixtureRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static io.themis.resolve.maven.MavenDependencyResolverTest.DEFAULTS;
import static io.themis.resolve.maven.MavenDependencyResolverTest.byKey;
import static io.themis.resolve.maven.MavenDependencyResolverTest.gav;
import static io.themis.resolve.maven.MavenDependencyResolverTest.keys;
import static io.themis.testing.FixtureRepo.deps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aggregator builds: reactor workspace, CI-friendly versions, per-version merge across modules. */
class MultiModuleResolutionTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;
    private ResolverOptions options;
    private Path root;

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
        options = repo.options();
        root = tmp.resolve("multi");
    }

    @Test
    void siblingsAreReactorNotThirdPartyAndModulesAreRecorded() {
        repo.publish("com.acme:lib-core:1.0", "jar", "");
        repo.publish("com.acme:lib-app:1.0", "jar", "");
        repo.publish("com.acme:shared:1.0", "jar", "");
        aggregator("com.acme:multi:1.0-SNAPSHOT", "", "core", "app");
        String parent = parent("com.acme:multi:1.0-SNAPSHOT");
        module("core", parent + deps("com.acme:lib-core:1.0", "com.acme:shared:1.0"));
        module("app", parent + deps("com.acme:core:${project.version}", "com.acme:lib-app:1.0", "com.acme:shared:1.0"));

        DependencyGraph graph = resolve();

        assertThat(graph.root()).isEqualTo(gav("com.acme:multi:1.0-SNAPSHOT"));
        Map<String, Dependency> byKey = byKey(graph);
        assertThat(byKey).containsOnlyKeys("com.acme:lib-core:1.0", "com.acme:lib-app:1.0", "com.acme:shared:1.0");
        Dependency libCore = byKey.get("com.acme:lib-core:1.0");
        assertThat(libCore.direct()).isTrue(); // declared in core's own POM
        assertThat(libCore.path()).extracting(Coordinate::toString)
                .containsExactly("com.acme:core:1.0-SNAPSHOT", "com.acme:lib-core:1.0");
        // reached by core directly and by app both directly and via core
        assertThat(libCore.modules()).extracting(Coordinate::toString)
                .containsExactly("com.acme:core:1.0-SNAPSHOT", "com.acme:app:1.0-SNAPSHOT");
        assertThat(byKey.get("com.acme:shared:1.0").modules()).hasSize(2);
        assertThat(byKey.get("com.acme:lib-app:1.0").modules()).extracting(Coordinate::toString)
                .containsExactly("com.acme:app:1.0-SNAPSHOT");
    }

    @Test
    void differentVersionsInDifferentModulesAreBothReported() {
        // /tmp/rev/rev2: core ships lang3 3.12.0, app (which also depends on core) mediates to its own 3.14.0.
        repo.publish("org.example:lang:3.12.0", "jar", "");
        repo.publish("org.example:lang:3.14.0", "jar", "");
        aggregator("com.acme:multi:1.2.3", "", "core", "app");
        String parent = parent("com.acme:multi:1.2.3");
        module("core", parent + deps("org.example:lang:3.12.0"));
        module("app", parent + deps("com.acme:core:${project.version}", "org.example:lang:3.14.0"));

        Map<String, Dependency> byKey = byKey(resolve());

        assertThat(byKey).containsOnlyKeys("org.example:lang:3.12.0", "org.example:lang:3.14.0");
        assertThat(byKey.get("org.example:lang:3.12.0").modules()).extracting(Coordinate::toString)
                .containsExactly("com.acme:core:1.2.3");
        assertThat(byKey.get("org.example:lang:3.14.0").modules()).extracting(Coordinate::toString)
                .containsExactly("com.acme:app:1.2.3");
    }

    @Test
    void sameArtifactInSeveralModulesTakesWidestScope() {
        repo.publish("com.acme:x:1.0", "jar", "");
        repo.publish("com.acme:y:1.0", "jar", "");
        aggregator("com.acme:multi:1.0", "", "m1", "m2", "m3");
        String parent = parent("com.acme:multi:1.0");
        module("m1", parent + deps("com.acme:x:1.0:provided", "com.acme:y:1.0:runtime"));
        module("m2", parent + deps("com.acme:x:1.0:runtime", "com.acme:y:1.0:provided"));
        module("m3", parent + deps("com.acme:y:1.0:compile"));

        Map<String, Dependency> byKey = byKey(resolve());

        assertThat(byKey.get("com.acme:x:1.0").scope()).isEqualTo(Scope.RUNTIME);
        assertThat(byKey.get("com.acme:y:1.0").scope()).isEqualTo(Scope.COMPILE);
        assertThat(byKey.get("com.acme:y:1.0").modules()).hasSize(3);
    }

    @Test
    void siblingBomImportedByTheRootResolvesFromTheReactor() {
        // /tmp/rev/bomr: the root imports a BOM that is itself a module of the build.
        repo.publish("org.example:lang:3.12.0", "jar", "");
        aggregator("com.acme:root:1.0", """
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.acme</groupId><artifactId>bom</artifactId><version>1.0</version>
                    <type>pom</type><scope>import</scope></dependency>
                </dependencies></dependencyManagement>
                """, "bom", "app");
        FixtureRepo.write(root.resolve("bom/pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId><artifactId>bom</artifactId><version>1.0</version><packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.example</groupId><artifactId>lang</artifactId><version>3.12.0</version></dependency>
                  </dependencies></dependencyManagement>
                </project>""");
        module("app", parent("com.acme:root:1.0") + deps("org.example:lang"));

        try (ProjectSession session = new MavenDependencyResolver(options).open(root, DEFAULTS)) {
            ProjectInfo info = session.project();
            assertThat(info.coordinate()).isEqualTo(gav("com.acme:root:1.0"));
            assertThat(keys(session.resolveDependencies())).containsOnly("org.example:lang:3.12.0");
        }
    }

    @Test
    void ciFriendlyRevisionVersionsResolveSiblingsFromTheReactor() {
        // /tmp/rev/rev: ${revision} everywhere; siblings must never be requested from a repository.
        repo.publish("org.example:lang:3.14.0", "jar", "");
        aggregator("com.acme:parent:${revision}", "<properties><revision>1.2.3</revision></properties>", "core", "app");
        String parent = parent("com.acme:parent:${revision}");
        module("core", parent + deps("org.example:lang:3.14.0"));
        module("app", parent + deps("com.acme:core:${project.version}"));

        try (ProjectSession session = new MavenDependencyResolver(options).open(root, DEFAULTS)) {
            assertThat(session.project().coordinate()).isEqualTo(gav("com.acme:parent:1.2.3"));
            Dependency lang = byKey(session.resolveDependencies()).get("org.example:lang:3.14.0");
            assertThat(lang.modules()).extracting(Coordinate::toString)
                    .containsExactly("com.acme:core:1.2.3", "com.acme:app:1.2.3");
        }
    }

    @Test
    void uninterpolatedModuleVersionFailsLoudly() {
        aggregator("com.acme:parent:${revision}", "", "core");
        module("core", parent("com.acme:parent:${revision}"));

        assertThatThrownBy(this::resolve)
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("${revision}");
    }

    // ---------------------------------------------------------------------------------------

    private DependencyGraph resolve() {
        return MavenDependencyResolverTest.resolve(options, root, DEFAULTS);
    }

    private void aggregator(String gav, String extraXml, String... modules) {
        StringBuilder m = new StringBuilder("<modules>");
        for (String module : modules) m.append("<module>").append(module).append("</module>");
        repo.project(root, gav, "pom", extraXml + m.append("</modules>"));
    }

    private static String parent(String gav) {
        String[] p = gav.split(":");
        return "<parent><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></parent>"
                .formatted(p[0], p[1], p[2]);
    }

    private void module(String artifactId, String body) {
        FixtureRepo.write(root.resolve(artifactId).resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion>
                  %s
                  <artifactId>%s</artifactId>
                </project>""".formatted(body, artifactId));
    }
}
