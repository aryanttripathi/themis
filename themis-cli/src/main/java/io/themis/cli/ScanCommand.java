package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ScanConfig;
import io.themis.model.Finding;
import io.themis.model.LicenseInfo;
import io.themis.model.ScanReport;
import io.themis.model.Severity;
import io.themis.report.JsonReportWriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "scan", mixinStandardHelpOptions = true, description = "Scan a project directory.")
final class ScanCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", defaultValue = ".", description = "Project directory (default: current).")
    Path projectDir;

    @Option(names = "--format", split = ",", defaultValue = "console",
            description = "Output formats: console, json (default: ${DEFAULT-VALUE}).")
    List<String> formats;

    @Option(names = "--output", defaultValue = "themis-report",
            description = "Directory for report files (default: ${DEFAULT-VALUE}).")
    Path outputDir;

    @Option(names = "--only", split = ",", description = "Run only these analyzers, e.g. licenses,javaversion.")
    Set<String> only = new HashSet<>();

    @Option(names = "--fail-on",
            description = "Exit 1 if any finding is at or above this severity: INFO, LOW, MEDIUM, HIGH, CRITICAL (default: HIGH, or failOn.severity in .themis.yml).")
    String failOn;

    @Option(names = "--config", description = "Config file (default: DIR/.themis.yml).")
    Path config;

    @Option(names = "--java-release", description = "Override the Java release detected from the POM.")
    Integer javaRelease;

    @Option(names = "--offline", description = "Use only the local cache and ~/.m2.")
    boolean offline;

    @Option(names = "--include-test", description = "Include test-scoped dependencies.")
    boolean includeTest;

    @Mixin
    ResolverMixin resolver;

    @ParentCommand
    ThemisCli parent;

    @Override
    public Integer call() throws Exception {
        ScanConfig cfg = CliConfig.build(projectDir, config, only, failOn, javaRelease, offline, includeTest);

        ScanReport report = Themis.defaultEngine(parent.environment().withResolver(resolver.apply(parent.environment().resolver())))
                .scan(projectDir, cfg);
        PrintWriter out = spec.commandLine().getOut();

        for (String format : formats) {
            switch (format.trim().toLowerCase(Locale.ROOT)) {
                case "console" -> printConsole(report, out);
                case "json" -> out.println("JSON report: " + new JsonReportWriter().write(report, outputDir));
                default -> {
                    spec.commandLine().getErr().println("error: unsupported format '" + format + "'");
                    return ThemisCli.EXIT_ERROR;
                }
            }
        }
        out.flush();
        DepsCommand.printWarnings(report.graph(), spec.commandLine().getErr());
        return report.hasFindingsAtLeast(cfg.failOn()) ? ThemisCli.EXIT_FINDINGS : ThemisCli.EXIT_OK;
    }

    private static void printConsole(ScanReport r, PrintWriter out) {
        out.println("themis " + r.toolVersion());
        out.println("Project      : " + r.project().coordinate() + " (Java " + r.project().javaRelease() + ")");
        out.println("Dependencies : " + r.graph().dependencies().size() + " (" + r.graph().directCount() + " direct)");

        if (!r.dataOf(LicenseInfo.class).isEmpty()) {
            out.println("Licenses     : " + LicensesCommand.summary(r.dataOf(LicenseInfo.class)));
        }
        Map<Severity, Long> counts = r.countBySeverity();
        out.printf(Locale.ROOT, "Findings     : %d critical, %d high, %d medium, %d low, %d info%n",
                counts.get(Severity.CRITICAL), counts.get(Severity.HIGH), counts.get(Severity.MEDIUM),
                counts.get(Severity.LOW), counts.get(Severity.INFO));

        for (Finding f : r.findings()) {
            out.printf(Locale.ROOT, "  [%-8s] %-13s %s%n", f.severity(), f.type(), f.title());
        }
        r.errors().forEach(e -> out.println("  analyzer '" + e.analyzer() + "' failed: " + e.message()));
        out.println("Completed in " + r.duration().toMillis() + " ms");
    }
}
