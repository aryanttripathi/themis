package io.themis.engine;

import io.themis.model.Finding;

import java.util.List;

/**
 * Output of one analyzer.
 *
 * @param findings reportable issues
 * @param data     typed details (e.g. LicenseInfo, JavaRequirement) that later stages and reporters can read
 */
public record AnalyzerResult(List<Finding> findings, List<Object> data) {

    public AnalyzerResult {
        findings = List.copyOf(findings);
        data = List.copyOf(data);
    }

    public static AnalyzerResult empty() {
        return new AnalyzerResult(List.of(), List.of());
    }
}
