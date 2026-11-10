package io.themis.cli;

import io.themis.Environment;
import io.themis.Themis;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "themis",
        mixinStandardHelpOptions = true,
        versionProvider = ThemisCli.VersionProvider.class,
        description = "Scans a Java project for dependency licenses, vulnerabilities, "
                + "Java-version compatibility, and leaked secrets/PII.",
        subcommands = {ScanCommand.class, DepsCommand.class, LicensesCommand.class, CommandLine.HelpCommand.class})
public final class ThemisCli implements Runnable {

    /** Exit codes (docs/HLD.md section 6). */
    public static final int EXIT_OK = 0;
    public static final int EXIT_FINDINGS = 1;
    public static final int EXIT_ERROR = 2;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /** Environment the subcommands start from ({@code --settings} etc. adjust its resolver options). */
    private final Environment environment;

    ThemisCli(Environment environment) {
        this.environment = environment;
    }

    Environment environment() {
        return environment;
    }

    public static void main(String[] args) {
        System.exit(newCommandLine(Environment.defaults()).execute(args));
    }

    /**
     * @param environment production passes {@link Environment#defaults()}; tests pass fixture Maven
     *                    settings, a temp cache and a stub deps.dev URL so nothing leaks in
     */
    static CommandLine newCommandLine(Environment environment) {
        return new CommandLine(new ThemisCli(environment))
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    cmd.getErr().println("error: " + ex.getMessage());
                    return EXIT_ERROR;
                });
        // picocli already returns 2 (CommandLine.ExitCode.USAGE) for invalid input, matching EXIT_ERROR.
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
