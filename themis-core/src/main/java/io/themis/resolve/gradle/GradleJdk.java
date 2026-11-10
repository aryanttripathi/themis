package io.themis.resolve.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks a JDK for running the project's Gradle. Themis itself needs Java 17+, but old Gradle
 * versions refuse newer JDKs (Gradle 6.x stops at 16), so Gradle may need a different JDK than ours.
 */
final class GradleJdk {

    private static final Pattern WRAPPER_VERSION = Pattern.compile("gradle-(\\d+(?:\\.\\d+){1,2}(?:-[a-z0-9-]+)?)-(?:bin|all)\\.zip");

    private GradleJdk() {
    }

    /** Newest JDK (feature release) that a Gradle version can run on (Gradle's compatibility matrix). */
    static int maxSupportedJdk(String gradleVersion) {
        String[] p = gradleVersion.split("[.-]");
        int major = Integer.parseInt(p[0]);
        int minor = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        if (major < 7) return 16;
        if (major == 7) return minor <= 2 ? 16 : minor <= 4 ? 17 : minor == 5 ? 18 : 19;
        if (major == 8) {
            if (minor <= 2) return 19;
            if (minor <= 4) return 20;
            if (minor <= 7) return 21;
            if (minor <= 9) return 22;
            if (minor <= 13) return 23;
            return 24;
        }
        return 25;
    }

    /** Gradle version from {@code gradle/wrapper/gradle-wrapper.properties}, if the project has a wrapper. */
    static Optional<String> wrapperVersion(Path projectDir) {
        Path props = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        if (!Files.isRegularFile(props)) return Optional.empty();
        try (InputStream in = Files.newInputStream(props)) {
            Properties p = new Properties();
            p.load(in);
            Matcher m = WRAPPER_VERSION.matcher(p.getProperty("distributionUrl", ""));
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * JDK to put in Gradle's JAVA_HOME, or empty to leave the environment alone: only when the
     * current JDK is too new for the project's Gradle, the newest installed compatible one (down to 8).
     */
    static Optional<Path> choose(Optional<String> gradleVersion, int currentJdk, IntFunction<Optional<Path>> installed) {
        if (gradleVersion.isEmpty()) return Optional.empty();
        int max;
        try {
            max = maxSupportedJdk(gradleVersion.get());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (currentJdk <= max) return Optional.empty();
        for (int v = max; v >= 8; v--) {
            Optional<Path> home = installed.apply(v);
            if (home.isPresent()) return home;
        }
        return Optional.empty();
    }

    /** macOS {@code /usr/libexec/java_home -F -v N}: the installed JDK of exactly that feature release. */
    static Optional<Path> macJavaHome(int feature) {
        Path tool = Path.of("/usr/libexec/java_home");
        if (!Files.isExecutable(tool)) return Optional.empty();
        try {
            Process p = new ProcessBuilder(tool.toString(), "-F", "-v", feature == 8 ? "1.8" : String.valueOf(feature))
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) return Optional.empty();
            Path home = Path.of(out.lines().reduce((a, b) -> b).orElse(out));
            // java_home -v N also matches newer versions on some macOS releases: insist on the feature release
            String release = Files.isRegularFile(home.resolve("release")) ? Files.readString(home.resolve("release")) : "";
            boolean matches = release.contains("JAVA_VERSION=\"" + feature + ".") || release.contains("JAVA_VERSION=\"" + feature + "\"")
                    || (feature == 8 && release.contains("JAVA_VERSION=\"1.8"));
            return matches ? Optional.of(home) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
