package io.themis.cli;

import io.themis.Themis;
import io.themis.resolve.maven.ResolverOptions;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "themis",
        mixinStandardHelpOptions = true,
        versionProvider = ThemisCli.VersionProvider.class,
        description = "Scans a Java project for dependency licenses, vulnerabilities, "
                + "Java-version compatibility, and leaked secrets/PII.",
        subcommands = {ScanCommand.class, DepsCommand.class, CommandLine.HelpCommand.class})
public final class ThemisCli implements Runnable {

    /** Exit codes (docs/HLD.md section 6). */
    public static final int EXIT_OK = 0;
    public static final int EXIT_FINDINGS = 1;
    public static final int EXIT_ERROR = 2;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    /** Resolver environment the subcommands start from ({@code --settings} etc. adjust it). */
    private final ResolverOptions baseOptions;

    ThemisCli(ResolverOptions baseOptions) {
        this.baseOptions = baseOptions;
    }

    ResolverOptions baseOptions() {
        return baseOptions;
    }

    public static void main(String[] args) {
        System.exit(newCommandLine(ResolverOptions.defaults()).execute(args));
    }

    /**
     * @param baseOptions Maven environment to resolve with; production passes {@link ResolverOptions#defaults()},
     *                    tests pass fixture options so nothing leaks in from the real environment
     */
    static CommandLine newCommandLine(ResolverOptions baseOptions) {
        return new CommandLine(new ThemisCli(baseOptions))
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
