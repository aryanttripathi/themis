package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ConfigException;
import io.themis.config.ConfigFile;
import io.themis.config.ConfigLoader;
import io.themis.config.ScanConfig;
import io.themis.model.Severity;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Builds the effective {@link ScanConfig}: built-in defaults, then .themis.yml (the project's own,
 * or {@code --config FILE}), then CLI flags. Flags only override what the user actually passed.
 */
final class CliConfig {

    private CliConfig() {
    }

    static ScanConfig build(Path projectDir, Path configFile, Set<String> only, String failOn,
                            Integer javaRelease, boolean offline, boolean includeTest) {
        ScanConfig config = ScanConfig.defaults();
        Optional<ConfigFile> file = ConfigLoader.load(projectDir, Optional.ofNullable(configFile), Themis.analyzerNames());
        if (file.isPresent()) {
            config = file.get().applyTo(config);
        }
        if (only != null && !only.isEmpty()) config = config.withEnabledAnalyzers(only);
        if (failOn != null) config = config.withFailOn(parseSeverity(failOn));
        if (javaRelease != null) config = config.withJavaRelease(OptionalInt.of(javaRelease));
        if (offline) config = config.withOffline(true);
        if (includeTest) config = config.withIncludeTestScope(true);
        return config;
    }

    /** Locale-independent (picocli's case-insensitive enum parsing breaks on "info" under tr-TR). */
    static Severity parseSeverity(String value) {
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid severity '" + value + "'; expected one of INFO, LOW, MEDIUM, HIGH, CRITICAL");
        }
    }
}
