package io.themis.resolve.maven;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where the Maven resolver takes its environment from. Production code uses {@link #defaults()};
 * tests build options around a generated settings.xml so they never touch the network or the real ~/.m2.
 *
 * @param userSettings          user settings.xml (local repository, mirrors, proxies, servers, profiles);
 *                              a missing file is treated as empty settings, as Maven does
 * @param globalSettings        global settings.xml (e.g. {@code $MAVEN_HOME/conf/settings.xml}), merged under the user file
 * @param localRepository       overrides the local repository from settings (like {@code -Dmaven.repo.local})
 * @param settingsSecurity      settings-security.xml holding the master password for encrypted passwords
 * @param ignorePomRepositories resolve only from settings (mirrors, profile repositories) and Central:
 *                              {@code <repositories>} declared in the project's POMs, their parents and
 *                              their dependencies' POMs are never contacted
 */
public record ResolverOptions(Path userSettings, Optional<Path> globalSettings, Optional<Path> localRepository,
                              Path settingsSecurity, boolean ignorePomRepositories) {

    public ResolverOptions {
        Objects.requireNonNull(userSettings, "userSettings");
        Objects.requireNonNull(globalSettings, "globalSettings");
        Objects.requireNonNull(localRepository, "localRepository");
        Objects.requireNonNull(settingsSecurity, "settingsSecurity");
    }

    /**
     * Maven's own defaults: {@code ~/.m2/settings.xml}, the global settings of the Maven
     * installation if one is known ({@code maven.home} system property, or {@code MAVEN_HOME} /
     * {@code M2_HOME}), {@code -Dmaven.repo.local} and {@code -Dsettings.security} if set.
     */
    public static ResolverOptions defaults() {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2");
        Optional<Path> global = Optional.ofNullable(System.getProperty("maven.home"))
                .or(() -> Optional.ofNullable(System.getenv("MAVEN_HOME")))
                .or(() -> Optional.ofNullable(System.getenv("M2_HOME")))
                .map(home -> Path.of(home, "conf", "settings.xml"));
        Optional<Path> local = Optional.ofNullable(System.getProperty("maven.repo.local")).map(Path::of);
        Path security = Optional.ofNullable(System.getProperty("settings.security")).map(Path::of)
                .orElse(m2.resolve("settings-security.xml"));
        return new ResolverOptions(m2.resolve("settings.xml"), global, local, security, false);
    }

    /** These options with a different user settings file (the CLI's {@code --settings}). */
    public ResolverOptions withUserSettings(Path settings) {
        return new ResolverOptions(settings, globalSettings, localRepository, settingsSecurity, ignorePomRepositories);
    }

    /** These options, optionally ignoring repositories declared in POMs (the CLI's {@code --ignore-pom-repositories}). */
    public ResolverOptions withIgnorePomRepositories(boolean ignore) {
        return new ResolverOptions(userSettings, globalSettings, localRepository, settingsSecurity, ignore);
    }
}
