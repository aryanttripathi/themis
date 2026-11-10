package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ScanConfig;
import io.themis.model.Dependency;
import io.themis.model.LicenseCategory;
import io.themis.model.LicenseInfo;
import io.themis.model.ScanReport;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code themis licenses [DIR]}: one row per dependency (coordinate, scope, SPDX, category, source),
 * most restrictive category first, then a summary count per category.
 */
@Command(name = "licenses", mixinStandardHelpOptions = true, description = "List dependency licenses by obligation.")
final class LicensesCommand implements Callable<Integer> {

    /** Display order: most restrictive / most in need of review first. */
    static final List<LicenseCategory> ORDER = List.of(
            LicenseCategory.NETWORK_COPYLEFT, LicenseCategory.STRONG_COPYLEFT, LicenseCategory.PROPRIETARY,
            LicenseCategory.UNKNOWN, LicenseCategory.WEAK_COPYLEFT, LicenseCategory.PERMISSIVE);

    @Spec
    CommandSpec spec;

    @ParentCommand
    ThemisCli parent;

    @Parameters(index = "0", defaultValue = ".", description = "Project directory (default: current).")
    Path projectDir;

    @Option(names = "--include-test", description = "Include test-scoped dependencies.")
    boolean includeTest;

    @Option(names = "--offline", description = "No network: local repository and cached deps.dev answers only.")
    boolean offline;

    @Option(names = "--config", description = "Config file (default: DIR/.themis.yml).")
    Path config;

    @Option(names = "--lenient", description = "Report unresolvable dependencies as warnings and scan the rest.")
    boolean lenient;

    @Mixin
    ResolverMixin resolver;

    @Override
    public Integer call() {
        ScanConfig cfg = CliConfig.build(projectDir, config, null, null, null, offline, includeTest).withLenient(lenient);
        ScanReport report = Themis.licenses(projectDir, cfg,
                parent.environment().withResolver(resolver.apply(parent.environment().resolver())));
        print(report, spec.commandLine().getOut());
        DepsCommand.printWarnings(report.graph(), spec.commandLine().getErr());
        report.errors().forEach(e -> spec.commandLine().getErr().println("error: analyzer '" + e.analyzer() + "' failed: " + e.message()));
        return report.errors().isEmpty() ? ThemisCli.EXIT_OK : ThemisCli.EXIT_ERROR;
    }

    static void print(ScanReport report, PrintWriter out) {
        Map<String, LicenseInfo> byCoordinate = new HashMap<>();
        report.dataOf(LicenseInfo.class).forEach(l -> byCoordinate.put(l.dependency().toString(), l));

        List<Dependency> deps = report.graph().dependencies().stream()
                .filter(d -> byCoordinate.containsKey(d.coordinate().toString()))
                .sorted(Comparator.comparingInt((Dependency d) -> ORDER.indexOf(byCoordinate.get(d.coordinate().toString()).category()))
                        .thenComparing(d -> d.coordinate().toString()))
                .toList();

        out.println(report.project().coordinate());
        int wCoord = width(deps.stream().map(d -> d.coordinate().toString()).toList());
        int wSpdx = width(deps.stream().map(d -> spdx(byCoordinate.get(d.coordinate().toString()))).toList());
        for (Dependency d : deps) {
            LicenseInfo l = byCoordinate.get(d.coordinate().toString());
            out.println(String.format(Locale.ROOT, "  %-" + wCoord + "s  %-8s  %-" + wSpdx + "s  %-16s  %s",
                    d.coordinate(), d.scope().mavenName(), spdx(l), l.category(), l.source()).stripTrailing());
        }
        out.println(deps.size() + " dependencies: " + summary(report.dataOf(LicenseInfo.class)));
        out.flush();
    }

    /** "1 network copyleft, 0 strong copyleft, ..., 2 permissive" in {@link #ORDER}. */
    static String summary(List<LicenseInfo> infos) {
        Map<LicenseCategory, Long> counts = new EnumMap<>(LicenseCategory.class);
        ORDER.forEach(c -> counts.put(c, 0L));
        infos.forEach(l -> counts.merge(l.category(), 1L, Long::sum));
        return ORDER.stream()
                .map(c -> counts.get(c) + " " + c.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .collect(Collectors.joining(", "));
    }

    private static String spdx(LicenseInfo l) {
        return l.spdx().orElse("-");
    }

    private static int width(List<String> values) {
        return Math.max(1, values.stream().mapToInt(String::length).max().orElse(1));
    }
}
