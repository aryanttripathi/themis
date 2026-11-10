package io.themis.cli;

import io.themis.Environment;
import io.themis.testing.FixtureRepo;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Runs the CLI against a fully hermetic {@link Environment}: fixture Maven settings, a temp cache
 * and a deps.dev URL that is either a local stub or nothing at all (port 9, discard).
 */
final class CliHarness {

    static final URI NO_DEPS_DEV = URI.create("http://127.0.0.1:9/");

    final StringWriter out = new StringWriter();
    final StringWriter err = new StringWriter();
    final Environment environment;

    CliHarness(FixtureRepo repo, Path tmp, URI depsDev) {
        // deps.dev and OSV share the stub (or the discard port): tests never reach a public API
        this.environment = new Environment(repo.options(), tmp.resolve("themis-cache"), depsDev,
                io.themis.resolve.gradle.GradleOptions.defaults(), depsDev);
    }

    CliHarness(FixtureRepo repo, Path tmp) {
        this(repo, tmp, NO_DEPS_DEV);
    }

    int run(String... args) {
        CommandLine cli = ThemisCli.newCommandLine(environment);
        cli.setOut(new PrintWriter(out, true));
        cli.setErr(new PrintWriter(err, true));
        return cli.execute(args);
    }

    int runIn(Locale locale, String... args) {
        return io.themis.testing.Locales.with(locale, (Supplier<Integer>) () -> run(args));
    }

    String out() {
        return out.toString();
    }

    String err() {
        return err.toString();
    }
}
