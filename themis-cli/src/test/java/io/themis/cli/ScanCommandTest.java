package io.themis.cli;

import io.themis.testing.FixtureRepo;
import io.themis.testing.Locales;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScanCommandTest {

    @TempDir
    Path tmp;

    @Test
    void failOnAcceptsLowerCaseSeverityUnderTurkishLocale() {
        // "high".toUpperCase() is "HİGH" in tr-TR; enum parsing must not depend on the default locale.
        FixtureRepo repo = FixtureRepo.create(tmp);
        Path app = repo.project(tmp.resolve("app"), "com.acme:app:1.0", "");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();

        int exit = Locales.with(Locales.TURKISH, () -> {
            CommandLine cli = ThemisCli.newCommandLine(
                    new io.themis.Environment(repo.options(), tmp.resolve("cache"), CliHarness.NO_DEPS_DEV));
            cli.setOut(new PrintWriter(out, true));
            cli.setErr(new PrintWriter(err, true));
            return cli.execute("scan", app.toString(), "--fail-on", "high");
        });

        assertThat(exit).as(err.toString()).isEqualTo(ThemisCli.EXIT_OK);
    }
}
