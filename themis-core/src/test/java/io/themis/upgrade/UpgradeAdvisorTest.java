package io.themis.upgrade;

import io.themis.cache.FileCache;
import io.themis.config.ScanConfig;
import io.themis.engine.AnalyzerResult;
import io.themis.engine.ScanContext;
import io.themis.engine.Stage;
import io.themis.license.DepsDevClient;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.ProjectInfo;
import io.themis.model.Scope;
import io.themis.model.Severity;
import io.themis.resolve.DependencyMetadata;
import io.themis.testing.HttpStub;
import io.themis.testing.HttpStub.Response;
import io.themis.vuln.Advisory;
import io.themis.vuln.DependencyVulnerabilities;
import io.themis.vuln.OsvClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class UpgradeAdvisorTest {

    @TempDir
    Path tmp;

    private final HttpStub server = HttpStub.start();   // deps.dev, OSV and "Maven Central" in one stub

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void identityAndStage() {
        assertThat(advisor().name()).isEqualTo("upgrade");
        assertThat(advisor().stage()).isEqualTo(Stage.ADVISOR);
    }

    @Test
    void recommendsTheLowestStableVersionWithoutKnownVulnerabilitiesThatRunsOnTheProjectsJava() throws Exception {
        versions("org.vendor:lib", "1.0", "1.1", "1.2-RC1", "1.2", "1.3", "2.0");
        // OSV: 1.1 still vulnerable, 1.2 clean
        server.on("/v1/querybatch", ok("{\"results\":[{\"vulns\":[{\"id\":\"GHSA-x\"}]},{},{},{}]}"));
        jar("org.vendor:lib:1.2", 52);   // Java 8

        AnalyzerResult r = advisor().analyze(context(17, vulnerable("org.vendor:lib:1.0", true, "GHSA-x", Severity.HIGH)));

        UpgradeSuggestion s = (UpgradeSuggestion) r.data().get(0);
        assertThat(s.recommended()).hasValue("1.2");
        assertThat(s.requiredJavaRelease()).hasValue(8);
        assertThat(s.sameMajor()).isTrue();
        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.type()).isEqualTo(FindingType.UPGRADE);
            assertThat(f.severity()).isEqualTo(Severity.INFO);
            assertThat(f.title()).isEqualTo("Upgrade org.vendor:lib 1.0 -> 1.2 (fixes 1 advisory; runs on Java 8+)");
        });
        // candidates skipped the pre-release and stopped at the first clean one
        assertThat(server.bodies().get(server.requests().indexOf("/v1/querybatch"))).contains("\"1.1\"").contains("\"1.2\"")
                .doesNotContain("1.2-RC1");
    }

    @Test
    void skipsVersionsThatNeedANewerJavaAndSaysSo() throws Exception {
        versions("org.vendor:lib", "1.0", "2.0");
        server.on("/v1/querybatch", ok("{\"results\":[{}]}"));
        jar("org.vendor:lib:2.0", 65);   // Java 21

        AnalyzerResult r = advisor().analyze(context(17, vulnerable("org.vendor:lib:1.0", true, "GHSA-x", Severity.CRITICAL)));

        UpgradeSuggestion s = (UpgradeSuggestion) r.data().get(0);
        assertThat(s.recommended()).isEmpty();
        assertThat(s.note()).contains("2.0").contains("Java 21").contains("project targets Java 17");
        assertThat(r.findings()).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.title()).contains("needs Java 21");
        });
    }

    @Test
    void transitiveDependenciesNameTheDirectDependencyToBump() throws Exception {
        versions("org.vendor:lib", "1.0", "1.1");
        server.on("/v1/querybatch", ok("{\"results\":[{}]}"));
        jar("org.vendor:lib:1.1", 55);
        Coordinate root = Coordinate.parse("org.app:app:1"), direct = Coordinate.parse("org.vendor:starter:3.0");
        Dependency dep = new Dependency(Coordinate.parse("org.vendor:lib:1.0"), Scope.COMPILE, false,
                List.of(root, direct, Coordinate.parse("org.vendor:lib:1.0")), Optional.empty());

        AnalyzerResult r = advisor().analyze(context(17, List.of(dep), List.of(vulns(dep.coordinate(), "GHSA-x", Severity.HIGH))));

        UpgradeSuggestion s = (UpgradeSuggestion) r.data().get(0);
        assertThat(s.viaDirect()).hasValue(direct);
        assertThat(r.findings().get(0).detail()).contains("org.vendor:starter:3.0").contains("override");
    }

    @Test
    void privateCoordinatesAreNeverLookedUp() throws Exception {
        AnalyzerResult r = advisor().analyze(context(17,
                vulnerable("org.app.internal:lib:1.0", true, "GHSA-x", Severity.HIGH)));   // project group org.app

        assertThat(server.requests()).isEmpty();
        assertThat(((UpgradeSuggestion) r.data().get(0)).note()).contains("private");
    }

    @Test
    void offlineUsesCachedAnswersOnly() throws Exception {
        assertThat(advisor().analyze(context(17, ScanConfig.defaults().withOffline(true),
                vulnerable("org.vendor:lib:1.0", true, "GHSA-x", Severity.HIGH))).data()).singleElement()
                .satisfies(d -> assertThat(((UpgradeSuggestion) d).note()).contains("offline"));
        assertThat(server.requests()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------------

    private UpgradeAdvisor advisor() {
        java.net.URI base = server.baseUrl();
        return new UpgradeAdvisor(DepsDevClient.create(base, Duration.ofMillis(1)), OsvClient.create(base, Duration.ofMillis(1)),
                new JarFetcher(base, tmp.resolve("jars")), new FileCache(tmp.resolve("cache"), Clock.systemUTC()));
    }

    private ScanContext context(int java, Object[] depAndVulns) {
        return context(java, ScanConfig.defaults(), depAndVulns);
    }

    private ScanContext context(int java, ScanConfig config, Object[] depAndVulns) {
        return context(java, config, List.of((Dependency) depAndVulns[0]), List.of((DependencyVulnerabilities) depAndVulns[1]));
    }

    private ScanContext context(int java, List<Dependency> deps, List<DependencyVulnerabilities> vulns) {
        return context(java, ScanConfig.defaults(), deps, vulns);
    }

    private ScanContext context(int java, ScanConfig config, List<Dependency> deps, List<DependencyVulnerabilities> vulns) {
        ProjectInfo project = new ProjectInfo(Path.of("/p"), Coordinate.parse("org.app:app:1"), java, List.of(), List.of());
        ScanContext ctx = new ScanContext(project, new DependencyGraph(project.coordinate(), deps), config, DependencyMetadata.NONE);
        ctx.publishForTest(List.copyOf(vulns));
        return ctx;
    }

    private static Object[] vulnerable(String gav, boolean direct, String id, Severity severity) {
        Coordinate c = Coordinate.parse(gav);
        Dependency d = new Dependency(c, Scope.COMPILE, direct, List.of(Coordinate.parse("org.app:app:1"), c), Optional.empty());
        return new Object[]{d, vulns(c, id, severity)};
    }

    private static DependencyVulnerabilities vulns(Coordinate c, String id, Severity severity) {
        Advisory a = new Advisory(id, List.of(), "bad", severity, Optional.empty(), List.of(), Optional.empty(), "u");
        return new DependencyVulnerabilities(c, List.of(a), Optional.empty(), Optional.empty());
    }

    private void versions(String ga, String... versions) {
        StringBuilder json = new StringBuilder("{\"versions\":[");
        for (int i = 0; i < versions.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"versionKey\":{\"version\":\"").append(versions[i]).append("\"}}");
        }
        server.on("/v3/systems/maven/packages/" + ga.replace(":", "%3A"), ok(json.append("]}").toString()));
    }

    private void jar(String gav, int classMajor) throws Exception {
        String[] p = gav.split(":");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(bytes)) {
            jos.putNextEntry(new JarEntry("x/A.class"));
            jos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, (byte) classMajor});
            jos.closeEntry();
        }
        server.onBytes("/maven2/" + p[0].replace('.', '/') + "/" + p[1] + "/" + p[2] + "/" + p[1] + "-" + p[2] + ".jar",
                bytes.toByteArray());
    }

    private static Response ok(String body) {
        return new Response(200, body);
    }

}
