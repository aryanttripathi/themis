package io.themis.license;

import io.themis.cache.FileCache;
import io.themis.config.LicensePolicy;
import io.themis.config.ScanConfig;
import io.themis.engine.AnalyzerResult;
import io.themis.engine.ScanContext;
import io.themis.engine.Stage;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.LicenseCategory;
import io.themis.model.LicenseInfo;
import io.themis.model.ProjectInfo;
import io.themis.model.Scope;
import io.themis.model.Severity;
import io.themis.resolve.DeclaredLicense;
import io.themis.resolve.DependencyMetadata;
import io.themis.resolve.PomLicenses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseAnalyzerTest {

    @TempDir
    Path tmp;

    private final Map<Coordinate, PomLicenses> poms = new HashMap<>();
    private final DependencyMetadata metadata = c -> poms.getOrDefault(c, PomLicenses.NONE);
    private final ProjectInfo project = new ProjectInfo(Path.of("/p"), Coordinate.parse("g:app:1"), 17, List.of(), List.of());

    @Test
    void identityAndStage() {
        LicenseAnalyzer analyzer = analyzer();
        assertThat(analyzer.name()).isEqualTo("licenses");
        assertThat(analyzer.stage()).isEqualTo(Stage.DEPENDENCY);
    }

    @Test
    void defaultPolicyMapsCategoriesToSeverities() throws Exception {
        List<Dependency> deps = List.of(
                dep("g:agpl:1", Scope.COMPILE, "GNU Affero General Public License v3"),
                dep("g:gpl:1", Scope.COMPILE, "GNU General Public License version 3"),
                dep("g:prop:1", Scope.COMPILE, "The JSON License"),
                dep("g:unknown:1", Scope.COMPILE, "Acme Internal"),
                dep("g:weak:1", Scope.RUNTIME, "Eclipse Public License 2.0"),
                dep("g:mit:1", Scope.COMPILE, "MIT License"));

        AnalyzerResult result = analyzer().analyze(context(deps, LicensePolicy.defaults()));

        assertThat(severities(result)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "g:agpl:1", Severity.HIGH, "g:gpl:1", Severity.HIGH, "g:prop:1", Severity.MEDIUM,
                "g:unknown:1", Severity.LOW, "g:weak:1", Severity.INFO));
        // data for every dependency, including the permissive one without a finding
        assertThat(result.data()).hasSize(6).allMatch(LicenseInfo.class::isInstance);
    }

    @Test
    void scopeAdjustsSeverity() throws Exception {
        List<Dependency> deps = List.of(
                dep("g:gpl-test:1", Scope.TEST, "GPL 3"),
                dep("g:agpl-test:1", Scope.TEST, "AGPL 3"),
                dep("g:gpl-provided:1", Scope.PROVIDED, "GPL 3"),
                dep("g:unknown-system:1", Scope.SYSTEM, "Acme Internal"),
                dep("g:weak-provided:1", Scope.PROVIDED, "EPL 2.0"));

        Map<String, Severity> s = severities(analyzer().analyze(context(deps, LicensePolicy.defaults())));

        assertThat(s).containsEntry("g:gpl-test:1", Severity.INFO)      // never distributed: capped at INFO
                .containsEntry("g:agpl-test:1", Severity.INFO)
                .containsEntry("g:gpl-provided:1", Severity.MEDIUM)      // HIGH lowered one level
                .containsEntry("g:unknown-system:1", Severity.INFO)      // LOW lowered one level
                .containsEntry("g:weak-provided:1", Severity.INFO);      // INFO stays INFO
    }

    @Test
    void allowArtifactsSuppressFindingsButKeepLicenseData() throws Exception {
        LicensePolicy policy = LicensePolicy.defaults().withAllowArtifacts(Set.of("org.mariadb.jdbc:mariadb-java-client"));
        List<Dependency> deps = List.of(dep("org.mariadb.jdbc:mariadb-java-client:3.5.1", Scope.COMPILE, "GPL 3"));

        AnalyzerResult result = analyzer().analyze(context(deps, policy));

        assertThat(result.findings()).isEmpty();
        assertThat(result.data()).singleElement().extracting(o -> ((LicenseInfo) o).category())
                .isEqualTo(LicenseCategory.STRONG_COPYLEFT);
    }

    @Test
    void customSeverityMapCanRaiseAndSilenceCategories() throws Exception {
        Map<LicenseCategory, Severity> map = new EnumMap<>(LicensePolicy.defaults().severities());
        map.put(LicenseCategory.NETWORK_COPYLEFT, Severity.CRITICAL);
        map.remove(LicenseCategory.UNKNOWN); // "NONE" in .themis.yml
        LicensePolicy policy = LicensePolicy.defaults().withSeverities(map);

        Map<String, Severity> s = severities(analyzer().analyze(context(List.of(
                dep("g:agpl:1", Scope.COMPILE, "AGPL 3"), dep("g:unknown:1", Scope.COMPILE, "Acme Internal")), policy)));

        assertThat(s).containsOnly(Map.entry("g:agpl:1", Severity.CRITICAL));
    }

    @Test
    void findingIsHumanReadableWithEvidence() throws Exception {
        AnalyzerResult result = analyzer().analyze(context(
                List.of(dep("com.itextpdf:kernel:8.0.5", Scope.COMPILE, "GNU Affero General Public License v3")),
                LicensePolicy.defaults()));

        Finding f = result.findings().get(0);
        assertThat(f.type()).isEqualTo(FindingType.LICENSE);
        assertThat(f.title()).isEqualTo("com.itextpdf:kernel:8.0.5 is AGPL-3.0-only (network copyleft)");
        assertThat(f.detail()).contains("network");
        assertThat(f.id()).isEqualTo("LICENSE:NETWORK_COPYLEFT:com.itextpdf:kernel:8.0.5");
        assertThat(f.dependency()).contains(Coordinate.parse("com.itextpdf:kernel:8.0.5"));
        assertThat(f.evidence()).containsEntry("spdx", "AGPL-3.0-only")
                .containsEntry("category", "NETWORK_COPYLEFT")
                .containsEntry("source", "pom")
                .containsEntry("scope", "compile")
                .containsEntry("declared", "GNU Affero General Public License v3");
    }

    @Test
    void unknownFindingExplainsWhatToDo() throws Exception {
        AnalyzerResult result = analyzer().analyze(context(
                List.of(dep("g:mystery:1", Scope.COMPILE, "Acme Internal")), LicensePolicy.defaults()));

        Finding f = result.findings().get(0);
        assertThat(f.title()).isEqualTo("g:mystery:1 has no recognised license (unknown)");
        assertThat(f.detail()).contains("licenses.overrides");
        assertThat(f.evidence()).containsEntry("declared", "Acme Internal").containsEntry("source", "none");
    }

    // ---------------------------------------------------------------------------------------

    private LicenseAnalyzer analyzer() {
        // ScanConfig.offline() is true in context(): deps.dev is never contacted (port 9 = discard).
        return new LicenseAnalyzer(new LicenseLookup(SpdxNormalizer.bundled(), new LicenseClassifier(),
                DepsDevClient.create(URI.create("http://127.0.0.1:9/")), new FileCache(tmp, Clock.systemUTC()),
                new LicenseTextMatcher()));
    }

    private ScanContext context(List<Dependency> deps, LicensePolicy policy) {
        ScanConfig config = new ScanConfig(Set.of(), Severity.HIGH, OptionalInt.empty(), true, true)
                .withLicensePolicy(policy);
        return new ScanContext(project, new DependencyGraph(project.coordinate(), deps), config, metadata);
    }

    private Dependency dep(String gav, Scope scope, String pomLicenseName) {
        Coordinate c = Coordinate.parse(gav);
        poms.put(c, new PomLicenses(List.of(new DeclaredLicense(pomLicenseName, null)), false, Optional.empty()));
        return new Dependency(c, scope, true, List.of(project.coordinate(), c), Optional.empty());
    }

    private static Map<String, Severity> severities(AnalyzerResult r) {
        return r.findings().stream().collect(Collectors.toMap(f -> f.dependency().orElseThrow().toString(),
                Finding::severity, (a, b) -> a, HashMap::new));
    }
}
