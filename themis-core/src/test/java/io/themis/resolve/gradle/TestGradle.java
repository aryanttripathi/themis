package io.themis.resolve.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Locates a Gradle installation for tests ({@code THEMIS_TEST_GRADLE}, {@code GRADLE_HOME}, or
 * {@code /opt/gradle-*}) and a Gradle user home under target/ shared by all tests, so the daemon is reused.
 */
final class TestGradle {

    private TestGradle() {
    }

    static Optional<Path> command() {
        String env = System.getenv("THEMIS_TEST_GRADLE");
        if (env != null && Files.isExecutable(Path.of(env))) return Optional.of(Path.of(env));
        String home = System.getenv("GRADLE_HOME");
        if (home != null && Files.isExecutable(Path.of(home, "bin", "gradle"))) return Optional.of(Path.of(home, "bin", "gradle"));
        try (var dirs = Files.list(Path.of("/opt"))) {
            return dirs.filter(d -> d.getFileName().toString().startsWith("gradle-"))
                    .map(d -> d.resolve("bin/gradle")).filter(Files::isExecutable).sorted().reduce((a, b) -> b);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static Path userHome() {
        return Path.of("target", "gradle-test-home").toAbsolutePath();
    }

    static void stopDaemons() {
        command().ifPresent(gradle -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(gradle.toString(), "--stop")
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.environment().put("GRADLE_USER_HOME", userHome().toString());
                Process p = pb.start();
                p.getOutputStream().close();
                p.waitFor(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort: an idle daemon times out on its own
            }
        });
    }
}
