package io.themis.model;

/** An analyzer that failed; the scan continues and reports this instead of aborting. */
public record AnalyzerError(String analyzer, String message) {
}
