package io.themis.resolve.maven;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.Scope;
import io.themis.model.Severity;
import io.themis.resolve.ProjectSession;
import io.themis.resolve.ResolutionException;
import io.themis.testing.FixtureRepo;
import io.themis.testing.Locales;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.themis.testing.FixtureRepo.deps;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Component tests against a generated on-disk repository: no network, no real ~/.m2.
 * Every scenario mirrors what {@code mvn dependency:list} / {@code dependency:tree} report for the same POMs.
 */
class MavenDependencyResolverTest {

    @TempDir
    Path tmp;

    private FixtureRepo repo;
    private ResolverOptions options;

    static final ScanConfig DEFAULTS = ScanConfig.defaults();
    static final ScanConfig WITH_TEST = new ScanConfig(Set.of(), Severity.HIGH, OptionalInt.empty(), false, true);
    static final ScanConfig OFFLINE = new ScanConfig(Set.of(), Severity.HIGH, OptionalInt.empty(), true, false);

    @BeforeEach
    void setUp() {
        repo = FixtureRepo.create(tmp);
        options = repo.options();
    }

    @Test
    void resolvesDirectAndTransitiveDependenciesWithPathScopeAndJar() {
        repo.publish("com.acme:b:1.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:b:1.0"));
        repo.publish("com.acme:r:1.0", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                deps("com.acme:a:1.0", "com.acme:r:1.0:runtime"));

        DependencyGraph graph = resolve(app, DEFAULTS);

        assertThat(graph.root()).isEqualTo(gav("com.acme:app:1.0"));
        Map<String, Dependency> byKey = byKey(graph);
        assertThat(byKey).containsOnlyKeys("com.acme:a:1.0", "com.acme:b:1.0", "com.acme:r:1.0");

        Dependency a = byKey.get("com.acme:a:1.0");
        assertThat(a.direct()).isTrue();
        assertThat(a.scope()).isEqualTo(Scope.COMPILE);
        assertThat(a.path()).containsExactly(gav("com.acme:app:1.0"), gav("com.acme:a:1.0"));
        assertThat(a.modules()).containsExactly(gav("com.acme:app:1.0"));

        Dependency b = byKey.get("com.acme:b:1.0");
        assertThat(b.direct()).isFalse();
        assertThat(b.path()).containsExactly(gav("com.acme:app:1.0"), gav("com.acme:a:1.0"), gav("com.acme:b:1.0"));
        assertThat(byKey.get("com.acme:r:1.0").scope()).isEqualTo(Scope.RUNTIME);

        assertThat(b.jarFile()).hasValueSatisfying(jar -> {
            assertThat(jar).exists().hasFileName("b-1.0.jar");
            assertThat(jar.toAbsolutePath().normalize()).startsWith(repo.localRepo().toAbsolutePath().normalize());
        });
        assertThat(graph.warnings()).isEmpty();
    }

    @Test
    void nearestDefinitionWinsAndConflictLoserIsOmitted() {
        // app -> a -> c:1.0          (depth 2, wins)
        // app -> b -> d -> c:2.0     (depth 3, loses)
        repo.publish("com.acme:c:1.0", "jar", "");
        repo.publish("com.acme:c:2.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:c:1.0"));
        repo.publish("com.acme:d:1.0", "jar", deps("com.acme:c:2.0"));
        repo.publish("com.acme:b:1.0", "jar", deps("com.acme:d:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:b:1.0", "com.acme:a:1.0"));

        DependencyGraph graph = resolve(app, DEFAULTS);

        assertThat(keys(graph)).containsExactlyInAnyOrder("com.acme:b:1.0", "com.acme:d:1.0", "com.acme:a:1.0", "com.acme:c:1.0");
        assertThat(byKey(graph).get("com.acme:c:1.0").path()).extracting(Coordinate::toString)
                .containsExactly("com.acme:app:1.0", "com.acme:a:1.0", "com.acme:c:1.0");
    }

    @Test
    void equalDepthFirstDeclarationWins() {
        repo.publish("com.acme:c:1.0", "jar", "");
        repo.publish("com.acme:c:2.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:c:1.0"));
        repo.publish("com.acme:b:1.0", "jar", deps("com.acme:c:2.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0", "com.acme:b:1.0"));

        assertThat(keys(resolve(app, DEFAULTS))).contains("com.acme:c:1.0").doesNotContain("com.acme:c:2.0");
    }

    @Test
    void testScopeIsExcludedByDefaultAndIncludedWithFlag() {
        repo.publish("com.acme:t-dep:1.0", "jar", "");
        repo.publish("com.acme:t:1.0", "jar", deps("com.acme:t-dep:1.0"));
        repo.publish("com.acme:main:1.0", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                deps("com.acme:main:1.0", "com.acme:t:1.0:test"));

        assertThat(keys(resolve(app, DEFAULTS))).containsOnly("com.acme:main:1.0");

        Map<String, Dependency> withTest = byKey(resolve(app, WITH_TEST));
        assertThat(withTest).containsOnlyKeys("com.acme:main:1.0", "com.acme:t:1.0", "com.acme:t-dep:1.0");
        assertThat(withTest.get("com.acme:t:1.0").scope()).isEqualTo(Scope.TEST);
        assertThat(withTest.get("com.acme:t-dep:1.0").scope()).isEqualTo(Scope.TEST);
        assertThat(withTest.get("com.acme:t-dep:1.0").direct()).isFalse();
    }

    @Test
    void transitiveCompileUnderRuntimeBecomesRuntime() {
        repo.publish("com.acme:b:1.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:b:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0:runtime"));

        assertThat(byKey(resolve(app, DEFAULTS)).get("com.acme:b:1.0").scope()).isEqualTo(Scope.RUNTIME);
    }

    @Test
    void directTestDeclarationOverridesTransitiveCompile() {
        // Maven: the scope of a direct declaration wins over the same artifact reached transitively.
        repo.publish("com.acme:b:1.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:b:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0", "com.acme:b:1.0:test"));

        assertThat(keys(resolve(app, DEFAULTS))).containsOnly("com.acme:a:1.0");
        Dependency b = byKey(resolve(app, WITH_TEST)).get("com.acme:b:1.0");
        assertThat(b.scope()).isEqualTo(Scope.TEST);
        assertThat(b.direct()).isTrue();
    }

    @Test
    void providedAndTheirTransitivesAreIncludedAsProvided() {
        repo.publish("com.acme:api-dep:1.0", "jar", "");
        repo.publish("com.acme:servlet-api:1.0", "jar", deps("com.acme:api-dep:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:servlet-api:1.0:provided"));

        Map<String, Dependency> byKey = byKey(resolve(app, DEFAULTS));

        assertThat(byKey.get("com.acme:servlet-api:1.0").scope()).isEqualTo(Scope.PROVIDED);
        assertThat(byKey.get("com.acme:api-dep:1.0").scope()).isEqualTo(Scope.PROVIDED);
    }

    @Test
    void systemScopeIsIncludedWithItsSystemPathAsJar() {
        Path jar = repo.publishJarWithoutPom("com.vendor:native:1.0"); // any jar file on disk
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", """
                <dependencies><dependency>
                  <groupId>com.vendor</groupId><artifactId>native</artifactId><version>1.0</version>
                  <scope>system</scope><systemPath>%s</systemPath>
                </dependency></dependencies>
                """.formatted(jar));

        Dependency d = byKey(resolve(app, DEFAULTS)).get("com.vendor:native:1.0");

        assertThat(d.scope()).isEqualTo(Scope.SYSTEM);
        assertThat(d.jarFile()).contains(jar);
    }

    @Test
    void optionalTransitiveDependencyIsExcluded() {
        repo.publish("com.acme:opt:1.0", "jar", "");
        repo.publish("com.acme:req:1.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:opt:1.0::optional", "com.acme:req:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        assertThat(keys(resolve(app, DEFAULTS))).containsOnly("com.acme:a:1.0", "com.acme:req:1.0");
    }

    @Test
    void exclusionsAreHonored() {
        repo.publish("com.acme:unwanted:1.0", "jar", "");
        repo.publish("com.acme:wanted:1.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:unwanted:1.0", "com.acme:wanted:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                FixtureRepo.depExcluding("com.acme:a:1.0", "com.acme:unwanted"));

        assertThat(keys(resolve(app, DEFAULTS))).containsOnly("com.acme:a:1.0", "com.acme:wanted:1.0");
    }

    @Test
    void classifiersAndTestJarAreDistinctDependencies() {
        repo.publish("com.acme:native:1.0", "jar", "");
        repo.publishClassified("com.acme:native:1.0", "linux-x86_64");
        repo.publishClassified("com.acme:native:1.0", "linux-aarch_64");
        repo.publish("com.acme:util:1.0", "jar", "");
        repo.publishClassified("com.acme:util:1.0", "tests");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", """
                <dependencies>
                  <dependency><groupId>com.acme</groupId><artifactId>native</artifactId><version>1.0</version>
                    <classifier>linux-x86_64</classifier></dependency>
                  <dependency><groupId>com.acme</groupId><artifactId>native</artifactId><version>1.0</version>
                    <classifier>linux-aarch_64</classifier></dependency>
                  <dependency><groupId>com.acme</groupId><artifactId>util</artifactId><version>1.0</version></dependency>
                  <dependency><groupId>com.acme</groupId><artifactId>util</artifactId><version>1.0</version>
                    <type>test-jar</type><scope>test</scope></dependency>
                </dependencies>
                """);

        Map<String, Dependency> byKey = byKey(resolve(app, WITH_TEST));

        assertThat(byKey).containsOnlyKeys("com.acme:native:jar:linux-x86_64:1.0", "com.acme:native:jar:linux-aarch_64:1.0",
                "com.acme:util:1.0", "com.acme:util:jar:tests:1.0");
        assertThat(byKey.get("com.acme:native:jar:linux-aarch_64:1.0").jarFile())
                .hasValueSatisfying(j -> assertThat(j).hasFileName("native-1.0-linux-aarch_64.jar"));
        Coordinate testJar = byKey.get("com.acme:util:jar:tests:1.0").coordinate();
        assertThat(testJar.classifier()).isEqualTo("tests");
        assertThat(testJar.extension()).isEqualTo("jar");
        assertThat(byKey.get("com.acme:util:jar:tests:1.0").scope()).isEqualTo(Scope.TEST);
        assertThat(byKey.get("com.acme:util:1.0").scope()).isEqualTo(Scope.COMPILE);
    }

    @Test
    void scopesMapCorrectlyUnderTurkishLocale() {
        // "compile".toUpperCase() is "COMPİLE" in tr-TR, so a locale-sensitive Scope.valueOf would throw.
        repo.publish("com.acme:a:1.0", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        DependencyGraph graph = Locales.with(Locales.TURKISH, () -> resolve(app, DEFAULTS));

        assertThat(graph.dependencies()).singleElement().extracting(Dependency::scope).isEqualTo(Scope.COMPILE);
    }

    @Test
    void importedBomPinsTransitiveVersionAndSuppliesDirectVersion() {
        repo.publish("com.acme:c:1.0", "jar", "");
        repo.publish("com.acme:c:2.0", "jar", "");
        repo.publish("com.acme:direct:3.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:c:1.0"));
        repo.publish("com.acme:bom:1.0", "pom", """
                <dependencyManagement>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>c</artifactId><version>2.0</version></dependency>
                    <dependency><groupId>com.acme</groupId><artifactId>direct</artifactId><version>3.0</version></dependency>
                  </dependencies>
                </dependencyManagement>
                """);
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", """
                <dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>com.acme</groupId><artifactId>bom</artifactId><version>1.0</version>
                      <type>pom</type><scope>import</scope>
                    </dependency>
                  </dependencies>
                </dependencyManagement>
                """ + deps("com.acme:a:1.0", "com.acme:direct"));

        assertThat(keys(resolve(app, DEFAULTS))).contains("com.acme:c:2.0", "com.acme:direct:3.0")
                .doesNotContain("com.acme:c:1.0").noneMatch(k -> k.contains("bom"));
    }

    @Test
    void projectDependencyManagementPinsTransitiveVersionAndScope() {
        repo.publish("com.acme:c:1.0", "jar", "");
        repo.publish("com.acme:c:2.0", "jar", "");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:c:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", """
                <dependencyManagement><dependencies>
                  <dependency><groupId>com.acme</groupId><artifactId>c</artifactId><version>2.0</version><scope>runtime</scope></dependency>
                </dependencies></dependencyManagement>
                """ + deps("com.acme:a:1.0"));

        Dependency c = byKey(resolve(app, DEFAULTS)).get("com.acme:c:2.0");

        assertThat(c).isNotNull();
        assertThat(c.scope()).isEqualTo(Scope.RUNTIME);
    }

    @Test
    void versionRangesResolveToHighestMatchForDependenciesAndParents() {
        repo.publish("com.acme:lib:1.0", "jar", "");
        repo.publish("com.acme:lib:1.5", "jar", "");
        repo.publish("com.acme:lib:2.0", "jar", "");
        repo.publish("com.acme:p:1", "pom", "<properties><from>p1</from></properties>");
        repo.publish("com.acme:p:2", "pom", "<properties><from>p2</from></properties>");
        repo.publish("com.acme:p:3", "pom", "<properties><from>p3</from></properties>");
        repo.publish("com.acme:marker-p2:1.0", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", """
                <parent><groupId>com.acme</groupId><artifactId>p</artifactId><version>[1,3)</version><relativePath/></parent>
                """ + deps("com.acme:lib:[1.0,2.0)", "com.acme:marker-${from}:1.0"));

        assertThat(keys(resolve(app, DEFAULTS))).containsOnly("com.acme:lib:1.5", "com.acme:marker-p2:1.0");
    }

    @Test
    void dependencyCycleTerminates() {
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:b:1.0"));
        repo.publish("com.acme:b:1.0", "jar", deps("com.acme:a:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        assertThat(keys(resolve(app, DEFAULTS))).containsOnly("com.acme:a:1.0", "com.acme:b:1.0");
    }

    @Test
    void remoteParentSuppliesPropertyAndManagedVersion() {
        repo.publish("com.acme:lib:1.1", "jar", "");
        repo.publish("com.acme:managed:4.0", "jar", "");
        repo.publish("com.acme:corp-parent:7", "pom", """
                <properties><lib.version>1.1</lib.version></properties>
                <dependencyManagement>
                  <dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>managed</artifactId><version>4.0</version></dependency>
                  </dependencies>
                </dependencyManagement>
                """);
        // Like spring-boot-starter-parent: not on disk next to the project, only in a repository.
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", """
                <parent>
                  <groupId>com.acme</groupId><artifactId>corp-parent</artifactId><version>7</version>
                  <relativePath/>
                </parent>
                """ + deps("com.acme:lib:${lib.version}", "com.acme:managed"));

        assertThat(keys(resolve(app, DEFAULTS))).containsOnly("com.acme:lib:1.1", "com.acme:managed:4.0");
    }

    @Test
    void missingAndInvalidTransitivePomsAreReportedAsWarnings() {
        repo.publishJarWithoutPom("com.acme:nopom:1.0");
        repo.publishRawPom("com.acme:badpom:1.0", "<project><this is not xml");
        repo.publish("com.acme:a:1.0", "jar", deps("com.acme:nopom:1.0", "com.acme:badpom:1.0"));
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        DependencyGraph graph = resolve(app, DEFAULTS);

        assertThat(keys(graph)).contains("com.acme:nopom:1.0", "com.acme:badpom:1.0");
        assertThat(graph.warnings()).contains(
                "The POM for com.acme:nopom:jar:1.0 is missing, no dependency information available");
        assertThat(graph.warnings()).anySatisfy(w -> assertThat(w)
                .startsWith("The POM for com.acme:badpom:jar:1.0 is invalid, transitive dependencies (if any) will not be available"));
    }

    @Test
    void unresolvableArtifactFailsWithClearMessage() {
        repo.publish("com.acme:ok:1.0", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                deps("com.acme:ok:1.0", "com.acme:missing:9.9"));

        assertThatThrownBy(() -> resolve(app, DEFAULTS))
                .isInstanceOf(ResolutionException.class)
                .hasMessageContaining("com.acme:app:1.0")
                .hasMessageContaining("com.acme:missing:jar:9.9")
                .satisfies(e -> assertThat(((ResolutionException) e).unresolved())
                        .containsExactly("com.acme:missing:jar:9.9"));
    }

    @Test
    void offlineModeUsesOnlyTheLocalRepository() {
        repo.publish("com.acme:a:1.0", "jar", "");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", deps("com.acme:a:1.0"));

        // Nothing cached yet: offline must not reach the (file) remote.
        assertThatThrownBy(() -> resolve(app, OFFLINE)).isInstanceOf(ResolutionException.class)
                .hasMessageContaining("com.acme:a");

        resolve(app, DEFAULTS); // populates the local repository
        assertThat(keys(resolve(app, OFFLINE))).containsOnly("com.acme:a:1.0");
    }

    @Test
    void sessionReportsEffectiveProjectFacts() {
        repo.publish("com.acme:corp-parent:7", "pom", "<properties><maven.compiler.release>11</maven.compiler.release></properties>");
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0",
                "<parent><groupId>com.acme</groupId><artifactId>corp-parent</artifactId><version>7</version><relativePath/></parent>");

        try (ProjectSession session = new MavenDependencyResolver(options).open(app, DEFAULTS)) {
            assertThat(session.project().coordinate()).isEqualTo(gav("com.acme:app:1.0"));
            assertThat(session.project().javaRelease()).isEqualTo(11);
            assertThat(session.resolveDependencies().dependencies()).isEmpty();
        }
    }

    @Test
    void supportsOnlyDirectoriesWithPom() throws IOException {
        MavenDependencyResolver resolver = new MavenDependencyResolver(options);
        assertThat(resolver.supports(tmp)).isFalse();
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        assertThat(resolver.supports(tmp)).isTrue();
    }

    // ---------------------------------------------------------------------------------------

    private DependencyGraph resolve(Path projectDir, ScanConfig config) {
        return resolve(options, projectDir, config);
    }

    static DependencyGraph resolve(ResolverOptions options, Path projectDir, ScanConfig config) {
        try (ProjectSession session = new MavenDependencyResolver(options).open(projectDir, config)) {
            return session.resolveDependencies();
        }
    }

    /** Keyed by the readable coordinate, e.g. {@code g:a:v} or {@code g:a:jar:classifier:v}. */
    static Map<String, Dependency> byKey(DependencyGraph graph) {
        return graph.dependencies().stream().collect(Collectors.toMap(d -> d.coordinate().toString(), Function.identity()));
    }

    static Set<String> keys(DependencyGraph graph) {
        return byKey(graph).keySet();
    }

    static Coordinate gav(String s) {
        return Coordinate.parse(s);
    }
}
