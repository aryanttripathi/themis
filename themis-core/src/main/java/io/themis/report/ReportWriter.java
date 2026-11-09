package io.themis.report;

import io.themis.model.ScanReport;

import java.io.IOException;
import java.nio.file.Path;

/** Renders a {@link ScanReport} into one output format. */
public interface ReportWriter {

    /** Format name used on the CLI, e.g. "json". */
    String format();

    /** Writes the report into {@code outputDir} and returns the written file. */
    Path write(ScanReport report, Path outputDir) throws IOException;
}
