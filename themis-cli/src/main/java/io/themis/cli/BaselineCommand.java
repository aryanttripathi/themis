package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ScanConfig;
import io.themis.model.ScanReport;
import io.themis.suppress.Baseline;
import io.themis.suppress.Suppressor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code themis baseline [DIR]}: scans and records every current finding (after {@code suppress:}
 * entries) in {@code .themis-baseline.json}. Commit the file: later scans only report new findings,
 * so an existing service can adopt Themis in CI without fixing everything first.
 */
@Command(name = "baseline", mixinStandardHelpOptions = true,
        description = "Record current findings so later scans report only new ones.")
final class BaselineCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @ParentCommand
    ThemisCli parent;

    @Parameters(index = "0", defaultValue = ".", description = "Project directory (default: current).")
    Path projectDir;

    @Option(names = "--file", description = "Baseline file to write (default: DIR/.themis-baseline.json).")
    Path file;

    @Option(names = "--only", split = ",", description = "Run only these analyzers.")
    Set<String> only = new HashSet<>();

    @Option(names = "--include-test", description = "Include test-scoped dependencies.")
    boolean includeTest;

    @Option(names = "--offline", description = "Use only the local cache and ~/.m2.")
    boolean offline;

    @Option(names = "--lenient", description = "Report unresolvable dependencies as warnings and scan the rest.")
    boolean lenient;

    @Option(names = "--config", description = "Config file (default: DIR/.themis.yml).")
    Path config;

    @Mixin
    ResolverMixin resolver;

    @Override
    public Integer call() throws Exception {
        ScanConfig cfg = CliConfig.build(projectDir, config, only, null, null, offline, includeTest).withLenient(lenient);
        ScanReport report = Themis.defaultEngine(parent.environment().withResolver(resolver.apply(parent.environment().resolver())))
                .scan(projectDir, cfg);
        report = Suppressor.apply(report, Suppressions.fromConfig(projectDir, config), Baseline.EMPTY, LocalDate.now());
        Path target = Suppressions.baselineFile(projectDir, file);
        Baseline.write(target, report);
        PrintWriter out = spec.commandLine().getOut();
        out.println("Baseline written: " + report.findings().size() + " findings accepted in " + target.toAbsolutePath());
        out.println("Commit it; later scans report only new findings (use --no-baseline to see everything).");
        DepsCommand.printWarnings(report.graph(), spec.commandLine().getErr());
        report.errors().forEach(e -> spec.commandLine().getErr().println("error: analyzer '" + e.analyzer() + "' failed: " + e.message()));
        return report.errors().isEmpty() ? ThemisCli.EXIT_OK : ThemisCli.EXIT_ERROR;
    }
}
