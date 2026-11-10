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

    @Test
    void sourceOnlyScansNeverTouchTheBuildTool() {
        io.themis.resolve.DependencyResolver exploding = new io.themis.resolve.DependencyResolver() {
            public boolean supports(Path p) { return true; }
            public io.themis.resolve.ProjectSession open(Path p, ScanConfig c) { throw new AssertionError("resolver used"); }
        };
        Analyzer source = analyzer("src", Stage.SOURCE, ctx -> new AnalyzerResult(List.of(Finding.forDependency(
                "r", FindingType.SECRET, Severity.HIGH, ctx.project().rootDir().getFileName().toString(), "", DEP, Map.of())), List.of()));

        ScanReport report = new ScanEngine(exploding, List.of(source), "test").scan(project, ScanConfig.defaults());

        assertThat(report.findings()).extracting(Finding::title).containsExactly(project.getFileName().toString());
        assertThat(report.graph().dependencies()).isEmpty();
    }

    private static AnalyzerResult result(Severity s) {
        return new AnalyzerResult(List.of(Finding.forDependency(s.name(), FindingType.VULNERABILITY, s, s.name(), "", DEP, Map.of())), List.of());
    }

    @Test
    void analyzersSeeSessionMetadataWhileTheSessionIsOpenAndDataReachesTheReport() throws IOException {
        writePom("17");
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        io.themis.resolve.DependencyResolver resolver = new io.themis.resolve.DependencyResolver() {
            public boolean supports(Path dir) { return true; }
            public io.themis.resolve.ProjectSession open(Path dir, ScanConfig cfg) {
                return new io.themis.resolve.ProjectSession() {
                    public io.themis.model.ProjectInfo project() {
                        return new io.themis.model.ProjectInfo(dir, DEP, 17, List.of(), List.of());
                    }
                    public io.themis.model.DependencyGraph resolveDependencies() {
                        return io.themis.model.DependencyGraph.empty(DEP);
                    }
                    public io.themis.resolve.PomLicenses pomLicenses(Coordinate c) {
                        if (closed.get()) throw new IllegalStateException("session already closed");
                        return new io.themis.resolve.PomLicenses(
                                List.of(new io.themis.resolve.DeclaredLicense("MIT License", null)), false, java.util.Optional.empty());
                    }
                    public void close() { closed.set(true); }
                };
            }
        };
        Analyzer reader = analyzer("reader", Stage.DEPENDENCY, ctx -> new AnalyzerResult(List.of(),
                List.of(ctx.metadata().pomLicenses(DEP).licenses().get(0).name())));

        ScanReport report = new ScanEngine(resolver, List.of(reader), "test").scan(project, ScanConfig.defaults());

        assertThat(report.errors()).isEmpty();
        assertThat(report.dataOf(String.class)).containsExactly("MIT License");
        assertThat(closed).isTrue();
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
