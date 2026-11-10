package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ScanConfig;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.Severity;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code themis deps [DIR]}: prints the resolved dependency list, one line per artifact and version
 * with its scope, whether it is direct or transitive, the declaring module(s) of a multi-module
 * build and the chain it was pulled in through. Warnings go to stderr; resolution errors propagate
 * to the CLI's execution exception handler ({@code error: ...}, exit 2).
 */
@Command(name = "deps", mixinStandardHelpOptions = true,
        description = "Resolve and list the project's dependencies (direct and transitive).")
final class DepsCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @ParentCommand
    ThemisCli parent;

    @Parameters(index = "0", defaultValue = ".", description = "Project directory (default: current).")
    Path projectDir;

    @Option(names = "--include-test", description = "Include test-scoped dependencies.")
    boolean includeTest;

    @Option(names = "--offline", description = "Resolve from the local repository only.")
    boolean offline;

    @Mixin
    ResolverMixin resolver;

    @Override
    public Integer call() {
        ScanConfig config = new ScanConfig(Set.of(), Severity.HIGH, OptionalInt.empty(), offline, includeTest);
        DependencyGraph graph = Themis.resolve(projectDir, config, parent.environment().withResolver(resolver.apply(parent.environment().resolver())));
        print(graph, spec.commandLine().getOut());
        printWarnings(graph, spec.commandLine().getErr());
        return ThemisCli.EXIT_OK;
    }

    static void print(DependencyGraph graph, PrintWriter out) {
        List<Dependency> deps = graph.dependencies();
        out.println(graph.root());
        out.println(deps.size() + " dependencies (" + graph.directCount() + " direct)");

        int width = deps.stream().mapToInt(d -> d.coordinate().toString().length()).max().orElse(0);
        for (Dependency d : deps) {
            String line = String.format(Locale.ROOT, "  %-" + width + "s  %-8s  %-10s  %s",
                    d.coordinate(), d.scope().mavenName(), d.direct() ? "direct" : "transitive",
                    origin(graph.root(), d));
            out.println(line.stripTrailing());
        }
        out.flush();
    }

    /** Non-fatal resolution problems, one {@code warning: ...} line each (stderr keeps stdout parseable). */
    static void printWarnings(DependencyGraph graph, PrintWriter err) {
        graph.warnings().forEach(w -> err.println("warning: " + w));
        err.flush();
    }

    /**
     * Where the dependency comes from: {@code via a:b:1 > c:d:2} (the chain between the declaring
     * module and the dependency); in a multi-module build preceded by the modules that reach it,
     * {@code in g:core:1, g:web:1 via c:d:2}.
     */
    private static String origin(Coordinate root, Dependency d) {
        List<Coordinate> path = d.path();
        String via = path.size() < 3 ? ""
                : "via " + path.subList(1, path.size() - 1).stream().map(Coordinate::toString)
                .collect(Collectors.joining(" > "));
        boolean singleModule = d.modules().isEmpty() || d.modules().equals(List.of(root));
        String in = singleModule ? ""
                : "in " + d.modules().stream().map(Coordinate::toString).collect(Collectors.joining(", "));
        return (in + " " + via).trim();
    }
}
