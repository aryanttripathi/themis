package io.themis.cli;

import io.themis.resolve.maven.ResolverOptions;
import picocli.CommandLine.Option;

import java.nio.file.Path;

/** Maven resolver flags shared by {@code scan} and {@code deps}. */
final class ResolverMixin {

    @Option(names = {"-s", "--settings"}, paramLabel = "FILE",
            description = "Maven user settings file (default: ~/.m2/settings.xml).")
    Path settings;

    @Option(names = "--ignore-pom-repositories",
            description = "Resolve only from settings.xml mirrors/profile repositories and Maven Central; "
                    + "never contact <repositories> declared in POMs (recommended for untrusted projects).")
    boolean ignorePomRepositories;

    /** The base options of the CLI with these flags applied. */
    ResolverOptions apply(ResolverOptions base) {
        ResolverOptions options = settings == null ? base : base.withUserSettings(settings);
        return ignorePomRepositories ? options.withIgnorePomRepositories(true) : options;
    }
}
