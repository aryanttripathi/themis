package io.themis.resolve.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.themis.resolve.ResolutionException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Runs {@code themisDependencies} through the project's Gradle and parses the JSON it writes. */
final class GradleRunner {

    private static final String INIT_SCRIPT = "themis-init.gradle";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final GradleOptions options;

    GradleRunner(GradleOptions options) {
        this.options = options;
    }

    GradleDump dump(Path projectDir, boolean includeTest, boolean offline) {
        Path work = null;
        try {
            work = Files.createTempDirectory("themis-gradle");
            Path init = work.resolve(INIT_SCRIPT);
            try (InputStream in = GradleRunner.class.getResourceAsStream(INIT_SCRIPT)) {
                if (in == null) throw new IllegalStateException("missing resource " + INIT_SCRIPT);
                Files.copy(in, init);
            }
            Path output = work.resolve("graph.json");
            Path log = work.resolve("gradle.log");

            List<String> cmd = new ArrayList<>(gradleCommand(projectDir));
            cmd.addAll(List.of("--init-script", init.toString(), "--console=plain", "-q",
                    "-Pthemis.output=" + output, "-Pthemis.includeTest=" + includeTest));
            if (offline) cmd.add("--offline");
            cmd.add("themisDependencies");

            ProcessBuilder pb = new ProcessBuilder(cmd).directory(projectDir.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            options.userHome().ifPresent(h -> pb.environment().put("GRADLE_USER_HOME", h.toString()));
            Process process = pb.start();
            process.getOutputStream().close();
            if (!process.waitFor(options.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new ResolutionException("Gradle did not finish within " + options.timeout().toMinutes()
                        + " min in " + projectDir, List.of(), null);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                throw new ResolutionException("Gradle failed (exit " + process.exitValue() + ") in " + projectDir
                        + ":\n" + summarize(readLines(log)), List.of(), null);
            }
            return JSON.readValue(output.toFile(), GradleDump.class);
        } catch (IOException e) {
            throw new ResolutionException("Cannot run Gradle in " + projectDir + ": " + e.getMessage(), List.of(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResolutionException("Interrupted while running Gradle in " + projectDir, List.of(), e);
        } finally {
            deleteQuietly(work);
        }
    }

    /** Explicit command, else the project's wrapper (run via sh: the exec bit is often lost), else gradle on PATH. */
    private List<String> gradleCommand(Path projectDir) {
        if (options.command().isPresent()) return List.of(options.command().get().toString());
        Path wrapper = projectDir.resolve("gradlew");
        if (Files.isRegularFile(wrapper)) return List.of("sh", wrapper.toString());
        return List.of("gradle");
    }

    private static List<String> readLines(Path log) {
        try {
            return Files.readAllLines(log);
        } catch (IOException | UncheckedIOException e) {
            return List.of("(no Gradle output)");
        }
    }

    /**
     * Gradle's "* What went wrong:" section (without stack frames), else the last 25 lines; plus a
     * hint for the most common failure: an old Gradle running on a JDK newer than it supports.
     */
    static String summarize(List<String> log) {
        int start = -1;
        for (int i = 0; i < log.size(); i++) {
            if (log.get(i).startsWith("* What went wrong")) { start = i; break; }
        }
        List<String> picked = new ArrayList<>();
        if (start >= 0) {
            for (int i = start; i < log.size() && !log.get(i).startsWith("* Try") && !log.get(i).startsWith("* Exception is"); i++) {
                if (!log.get(i).trim().startsWith("at ")) picked.add(log.get(i));
            }
            if (picked.size() > 40) picked = picked.subList(0, 40);
        } else {
            picked = log.subList(Math.max(0, log.size() - 25), log.size());
        }
        String text = String.join("\n", picked).strip();
        String all = String.join("\n", log);
        if (all.contains("Unsupported class file major version") || all.contains("Unsupported Java")) {
            text += "\nhint: this Gradle version cannot run on the current JDK. Point JAVA_HOME at a JDK it supports, e.g. "
                    + "JAVA_HOME=$(/usr/libexec/java_home -v 11) themis scan .  (Gradle 6.x: JDK <= 16, 7.0-7.2: <= 16, 7.3-7.5: <= 18, 7.6: <= 19)";
        }
        return text;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // temp dir; the OS cleans it up eventually
        }
    }
}
