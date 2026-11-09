package io.themis.engine;

import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.ScanReport;
import io.themis.model.Severity;
import io.themis.resolve.EmptyDependencyResolver;
import io.themis.resolve.maven.MavenDependencyResolver;
import io.themis.testing.FixtureRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ScanEngineTest {

    @TempDir
    Path project;

    @TempDir
    Path fixtures;

    private static final Coordinate DEP = Coordinate.parse("com.example:lib:1.0");

    @Test
    void failingAnalyzerIsReportedAndOthersStillRun() throws IOException {
        writePom("17");
        Analyzer ok = analyzer("ok", Stage.DEPENDENCY, ctx -> new AnalyzerResult(
                List.of(Finding.forDependency("r1", FindingType.LICENSE, Severity.HIGH, "t", "d", DEP, Map.of())),
                List.of()));
        Analyzer boom = analyzer("boom", Stage.SOURCE, ctx -> {
            throw new IllegalStateException("kaboom");
        });

        ScanReport report = engine(ok, boom).scan(project, ScanConfig.defaults());

        assertThat(report.findings()).hasSize(1);
        assertThat(report.errors()).singleElement()
                .satisfies(e -> assertThat(e.analyzer()).isEqualTo("boom"))
                .satisfies(e -> assertThat(e.message()).contains("kaboom"));
    }

    @Test
    void advisorSeesDataPublishedByEarlierStage() throws IOException {
        writePom("17");
        Analyzer producer = analyzer("producer", Stage.DEPENDENCY, ctx -> new AnalyzerResult(List.of(), List.of("hello")));
        Analyzer advisor = analyzer("advisor", Stage.ADVISOR, ctx -> {
            String seen = String.join(",", ctx.resultsOf(String.class));
            return new AnalyzerResult(List.of(Finding.forDependency(
                    seen, FindingType.UPGRADE, Severity.INFO, seen, "", DEP, Map.of())), List.of());
        });

        ScanReport report = engine(advisor, producer).scan(project, ScanConfig.defaults());

        assertThat(report.findings()).extracting(Finding::title).containsExactly("hello");
    }

    @Test
    void onlyFlagRestrictsAnalyzersAndFindingsAreSortedBySeverity() throws IOException {
        writePom("1.8");
        Analyzer low = analyzer("low", Stage.DEPENDENCY, ctx -> result(Severity.LOW));
        Analyzer critical = analyzer("critical", Stage.DEPENDENCY, ctx -> result(Severity.CRITICAL));
        Analyzer skipped = analyzer("skipped", Stage.DEPENDENCY, ctx -> result(Severity.HIGH));
        ScanConfig cfg = new ScanConfig(Set.of("low", "critical"), Severity.HIGH, OptionalInt.empty(), false, false);

        ScanReport report = engine(low, critical, skipped).scan(project, cfg);

        assertThat(report.project().javaRelease()).isEqualTo(8);
        assertThat(report.findings()).extracting(Finding::severity).containsExactly(Severity.CRITICAL, Severity.LOW);
    }

    private static AnalyzerResult result(Severity s) {
        return new AnalyzerResult(List.of(Finding.forDependency(s.name(), FindingType.VULNERABILITY, s, s.name(), "", DEP, Map.of())), List.of());
    }

    private ScanEngine engine(Analyzer... analyzers) {
        // Project facts from the real Maven model, hermetic settings, no dependency resolution.
        MavenDependencyResolver maven = new MavenDependencyResolver(FixtureRepo.create(fixtures).options());
        return new ScanEngine(new EmptyDependencyResolver(maven), List.of(analyzers), "test");
    }

    private void writePom(String javaVersion) throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0.0</version>
                  <properties><java.version>%s</java.version></properties>
                </project>
                """.formatted(javaVersion));
    }

    @FunctionalInterface
    interface Body {
        AnalyzerResult run(ScanContext ctx) throws Exception;
    }

    private static Analyzer analyzer(String name, Stage stage, Body body) {
        return new Analyzer() {
            public String name() { return name; }
            public Stage stage() { return stage; }
            public AnalyzerResult analyze(ScanContext ctx) throws Exception { return body.run(ctx); }
        };
    }
}
