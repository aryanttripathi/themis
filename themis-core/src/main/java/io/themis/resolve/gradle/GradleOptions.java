package io.themis.resolve.gradle;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * How to run Gradle.
 *
 * @param command  Gradle executable to use; empty means the project's own {@code ./gradlew}, else
 *                 {@code gradle} on the PATH
 * @param userHome {@code GRADLE_USER_HOME} override; empty keeps the user's (and with it their
 *                 credentials in {@code gradle.properties} and the dependency cache)
 * @param timeout  give up on a Gradle run after this long
 */
public record GradleOptions(Optional<Path> command, Optional<Path> userHome, Duration timeout) {

    public GradleOptions {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(timeout, "timeout");
    }

    public static GradleOptions defaults() {
        return new GradleOptions(Optional.empty(), Optional.empty(), Duration.ofMinutes(10));
    }
}
