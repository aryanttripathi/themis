package io.themis.engine;

import io.themis.config.ScanConfig;
import io.themis.model.AnalyzerError;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.ProjectInfo;
import io.themis.model.ScanReport;
import io.themis.resolve.DependencyResolver;
import io.themis.resolve.ProjectSession;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates a scan: open a project session (model + dependency graph), run analyzers stage by stage.
 * A failing analyzer is recorded as an {@link AnalyzerError}; it never aborts the scan.
 */
public final class ScanEngine {

    private final DependencyResolver resolver;
    private final List<Analyzer> analyzers;
    private final String toolVersion;

    public ScanEngine(DependencyResolver resolver, List<Analyzer> analyzers, String toolVersion) {
        this.resolver = resolver;
        this.analyzers = List.copyOf(analyzers);
        this.toolVersion = toolVersion;
    }

    public ScanReport scan(Path projectDir, ScanConfig config) {
        Instant start = Instant.now();

        List<Finding> findings = new CopyOnWriteArrayList<>();
        List<AnalyzerError> errors = new CopyOnWriteArrayList<>();
        ProjectInfo project;
        DependencyGraph graph;
        ScanContext ctx;
        // Source-only scans (secrets, PII) need no dependency graph: never run Maven/Gradle or touch the network.
        if (enabled(config, Stage.DEPENDENCY, Stage.ADVISOR).isEmpty()) {
            Path root = projectDir.toAbsolutePath().normalize();
            project = new ProjectInfo(root, new io.themis.model.Coordinate("local", String.valueOf(root.getFileName()), "-"),
                    io.themis.resolve.JavaRelease.DEFAULT, List.of(), List.of());
            graph = DependencyGraph.empty(project.coordinate());
            ctx = new ScanContext(project, graph, config, io.themis.resolve.DependencyMetadata.NONE);
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                runAll(pool, ctx, enabled(config, Stage.SOURCE), findings, errors);
            } finally {
                pool.shutdownNow();
            }
            return finish(start, project, graph, findings, errors, ctx);
        }
        // The session stays open while analyzers run: they read dependency metadata (POM licenses) from it.
        try (ProjectSession session = resolver.open(projectDir, config)) {
            project = session.project();
            graph = session.resolveDependencies();
            ctx = new ScanContext(project, graph, config, session);

            int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                // DEPENDENCY and SOURCE analyzers are independent: run them together.
                runAll(pool, ctx, enabled(config, Stage.DEPENDENCY, Stage.SOURCE), findings, errors);
                runAll(pool, ctx, enabled(config, Stage.ADVISOR), findings, errors);
            } finally {
                pool.shutdownNow();
            }
        }

        return finish(start, project, graph, findings, errors, ctx);
    }

    private ScanReport finish(Instant start, ProjectInfo project, DependencyGraph graph, List<Finding> findings,
                              List<AnalyzerError> errors, ScanContext ctx) {
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparing(Finding::severity).reversed()
                .thenComparing(Finding::type)
                .thenComparing(Finding::id));
        return new ScanReport(toolVersion, start, Duration.between(start, Instant.now()),
                project, graph, sorted, errors, ctx.allData());
    }

    private List<Analyzer> enabled(ScanConfig config, Stage... stages) {
        List<Stage> wanted = List.of(stages);
        return analyzers.stream()
                .filter(a -> wanted.contains(a.stage()))
                .filter(a -> config.isEnabled(a.name()))
                .toList();
    }

    private void runAll(ExecutorService pool, ScanContext ctx, List<Analyzer> batch,
                        List<Finding> findings, List<AnalyzerError> errors) {
        List<Future<AnalyzerResult>> futures = new ArrayList<>();
        for (Analyzer a : batch) {
            futures.add(pool.submit(() -> a.analyze(ctx)));
        }
        for (int i = 0; i < batch.size(); i++) {
            Analyzer a = batch.get(i);
            try {
                AnalyzerResult result = futures.get(i).get();
                findings.addAll(result.findings());
                ctx.publish(result.data());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(new AnalyzerError(a.name(), cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                errors.add(new AnalyzerError(a.name(), "interrupted"));
            }
        }
    }
}
