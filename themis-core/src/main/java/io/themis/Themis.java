package io.themis;

import io.themis.config.ScanConfig;
import io.themis.engine.Analyzer;
import io.themis.engine.ScanEngine;
import io.themis.javaversion.JavaVersionAnalyzer;
import io.themis.model.ScanReport;
import io.themis.resolve.EmptyDependencyResolver;
import io.themis.resolve.ProjectInspector;

import java.nio.file.Path;
import java.util.List;

/**
 * Library entry point.
 * <pre>{@code
 * ScanReport report = Themis.scan(Path.of("."), ScanConfig.defaults());
 * }</pre>
 */
public final class Themis {

    public static final String VERSION = "0.1.0-SNAPSHOT";

    private Themis() {
    }

    /** Analyzers wired by default. New analyzers are registered here as phases land. */
    public static List<Analyzer> defaultAnalyzers() {
        return List.of(new JavaVersionAnalyzer());
    }

    public static ScanEngine defaultEngine() {
        return new ScanEngine(new ProjectInspector(), new EmptyDependencyResolver(), defaultAnalyzers(), VERSION);
    }

    public static ScanReport scan(Path projectDir, ScanConfig config) {
        return defaultEngine().scan(projectDir, config);
    }
}
