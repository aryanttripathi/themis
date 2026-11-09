package io.themis.engine;

/**
 * A single concern (licenses, vulnerabilities, secrets...). Implementations must be stateless
 * and thread-safe: analyzers in the same stage run concurrently.
 */
public interface Analyzer {

    /** Short name used in config and CLI, e.g. "licenses". */
    String name();

    Stage stage();

    AnalyzerResult analyze(ScanContext context) throws Exception;
}
