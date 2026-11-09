package io.themis.cli;

import io.themis.Themis;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "themis",
        mixinStandardHelpOptions = true,
        versionProvider = ThemisCli.VersionProvider.class,
        description = "Scans a Java project for dependency licenses, vulnerabilities, "
                + "Java-version compatibility, and leaked secrets/PII.",
        subcommands = {ScanCommand.class, CommandLine.HelpCommand.class})
public final class ThemisCli implements Runnable {

    /** Exit codes (docs/HLD.md section 6). */
    public static final int EXIT_OK = 0;
    public static final int EXIT_FINDINGS = 1;
    public static final int EXIT_ERROR = 2;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(newCommandLine().execute(args));
    }

    static CommandLine newCommandLine() {
        return new CommandLine(new ThemisCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println("error: " + ex.getMessage());
                    return EXIT_ERROR;
                })
                .setExitCodeOnInvalidInput(EXIT_ERROR);
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"themis " + Themis.VERSION};
        }
    }
}
