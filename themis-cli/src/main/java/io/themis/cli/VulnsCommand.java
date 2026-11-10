package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ScanConfig;
import io.themis.model.ScanReport;
import io.themis.vuln.Advisory;
import io.themis.vuln.DependencyVulnerabilities;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code themis vulns [DIR]}: known vulnerabilities per dependency (OSV), most severe first, with the
 * version that fixes them. Internal packages and snapshots are never sent to OSV.
 */
@Command(name = "vulns", mixinStandardHelpOptions = true, description = "List known vulnerabilities in dependencies (OSV).")
final class VulnsCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @ParentCommand
    ThemisCli parent;

    @Parameters(index = "0", defaultValue = ".", description = "Project directory (default: current).")
    Path projectDir;

    @Option(names = "--include-test", description = "Include test-scoped dependencies.")
    boolean includeTest;

    @Option(names = "--offline", description = "No network: cached OSV answers only.")
    boolean offline;

    @Option(names = "--lenient", description = "Report unresolvable dependencies as warnings and check the rest.")
    boolean lenient;

    @Option(names = "--config", description = "Config file (default: DIR/.themis.yml).")
    Path config;

    @Option(names = "--console-limit", description = "Above this many rows, print a summary and write an HTML report instead.")
    Integer consoleLimit;

    @Option(names = "--no-open", description = "Do not open the HTML report in a browser.")
    boolean noOpen;

    @Option(names = "--output", defaultValue = "themis-report", description = "Directory for the HTML report (default: ${DEFAULT-VALUE}).")
    Path outputDir;

    @Mixin
    ResolverMixin resolver;

    @Override
    public Integer call() {
        ScanConfig cfg = CliConfig.build(projectDir, config, null, null, null, offline, includeTest).withLenient(lenient);
        ScanReport report = Themis.vulnerabilities(projectDir, cfg,
                parent.environment().withResolver(resolver.apply(parent.environment().resolver())));
        ReportOutput.Settings rs = ReportOutput.settings(projectDir, config, consoleLimit, noOpen);
        int rows = report.dataOf(io.themis.vuln.DependencyVulnerabilities.class).stream().mapToInt(v -> v.advisories().size()).sum();
        if (rows > rs.consoleLimit()) {
            PrintWriter out = spec.commandLine().getOut();
            out.println(report.project().coordinate());
            out.println(summaryLine(report));
            ReportOutput.tooMany(out, rows, "advisories", rs.consoleLimit());
            try {
                ReportOutput.writeHtml(report, outputDir, rs.open(), out);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        } else {
            print(report, spec.commandLine().getOut());
        }
        DepsCommand.printWarnings(report.graph(), spec.commandLine().getErr());
        report.errors().forEach(e -> spec.commandLine().getErr().println("error: analyzer '" + e.analyzer() + "' failed: " + e.message()));
        return report.errors().isEmpty() ? ThemisCli.EXIT_OK : ThemisCli.EXIT_ERROR;
    }

    private static String summaryLine(ScanReport report) {
        return summary(report.dataOf(DependencyVulnerabilities.class));
    }

    static void print(ScanReport report, PrintWriter out) {
        List<DependencyVulnerabilities> all = report.dataOf(DependencyVulnerabilities.class);
        List<DependencyVulnerabilities> affected = all.stream().filter(v -> !v.advisories().isEmpty())
                .sorted(Comparator.comparing((DependencyVulnerabilities v) -> worst(v)).reversed()
                        .thenComparing(v -> v.dependency().toString()))
                .toList();
        out.println(report.project().coordinate());
        for (DependencyVulnerabilities v : affected) {
            out.println("  " + v.dependency() + "  (" + v.advisories().size() + (v.advisories().size() == 1 ? " advisory" : " advisories")
                    + v.fixedIn().map(f -> ", upgrade to " + f).orElse(", no fix yet") + ")");
            v.advisories().stream().sorted(Comparator.comparing(Advisory::severity).reversed()).forEach(a ->
                    out.println(String.format(Locale.ROOT, "    %-8s %-22s %-16s %s", a.severity(), a.id(),
                            a.aliases().stream().filter(x -> x.startsWith("CVE-")).findFirst().orElse(""),
                            a.summary()).stripTrailing()));
        }
        out.println(summary(all));
        out.flush();
    }

    /** "3 vulnerable dependencies, 7 advisories; 4 private dependencies not sent to OSV". */
    static String summary(List<DependencyVulnerabilities> all) {
        long vulnerable = all.stream().filter(v -> !v.advisories().isEmpty()).count();
        long advisories = all.stream().mapToLong(v -> v.advisories().size()).sum();
        long notChecked = all.stream().filter(v -> v.notChecked().isPresent()).count();
        return vulnerable + " vulnerable dependencies, " + advisories + " advisories, " + all.size() + " checked"
                + (notChecked == 0 ? "" : "; " + notChecked + " not checked (private or offline: never sent to OSV)");
    }

    private static io.themis.model.Severity worst(DependencyVulnerabilities v) {
        return v.advisories().stream().map(Advisory::severity).max(Comparator.naturalOrder()).orElse(io.themis.model.Severity.INFO);
    }
}
