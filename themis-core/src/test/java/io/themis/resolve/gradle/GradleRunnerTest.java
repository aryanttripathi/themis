package io.themis.resolve.gradle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GradleRunnerTest {

    @Test
    void failureSummaryShowsWhatWentWrongNotJustTheStackTrace() {
        List<String> log = new ArrayList<>(List.of("FAILURE: Build failed with an exception.", "", "* What went wrong:",
                "Could not compile build file '/x/build.gradle'.", "> startup failed:",
                "  General error during semantic analysis: Unsupported class file major version 61"));
        for (int i = 0; i < 60; i++) log.add("  \tat org.gradle.Some.frame" + i + "(Some.java:1)");
        log.addAll(List.of("1 error", "", "* Try:", "Run with --stacktrace", "", "BUILD FAILED in 1s"));

        String summary = GradleRunner.summarize(log);

        assertThat(summary).contains("Could not compile build file").contains("Unsupported class file major version 61")
                .doesNotContain("frame59").doesNotContain("* Try:");
        assertThat(summary).contains("hint:").contains("THEMIS_GRADLE_JAVA_HOME");
    }

    @Test
    void withoutAWhatWentWrongSectionTheTailIsShown() {
        List<String> log = new ArrayList<>();
        for (int i = 0; i < 100; i++) log.add("line " + i);

        String summary = GradleRunner.summarize(log);

        assertThat(summary).contains("line 99").doesNotContain("line 10\n").doesNotContain("hint:");
    }
}
