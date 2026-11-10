package io.themis.resolve.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GradleJdkTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "Gradle {0} runs on JDK <= {1}")
    @CsvSource({"6.9.1,16", "7.2,16", "7.3,17", "7.5.1,18", "7.6.1,19", "8.2,19", "8.4,20", "8.5,21",
            "8.8,22", "8.11.1,23", "8.13,23", "8.14,24", "9.0.0,25"})
    void knowsTheNewestJdkEachGradleSupports(String gradle, int jdk) {
        assertThat(GradleJdk.maxSupportedJdk(gradle)).isEqualTo(jdk);
    }

    @Test
    void readsTheWrapperVersion() throws Exception {
        Files.createDirectories(dir.resolve("gradle/wrapper"));
        Files.writeString(dir.resolve("gradle/wrapper/gradle-wrapper.properties"),
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-6.9.1-bin.zip\n");

        assertThat(GradleJdk.wrapperVersion(dir)).hasValue("6.9.1");
        assertThat(GradleJdk.wrapperVersion(dir.resolve("nope"))).isEmpty();
    }

    @Test
    void keepsTheCurrentJdkWhenGradleSupportsIt() {
        assertThat(GradleJdk.choose(Optional.of("8.8"), 17, v -> Optional.of(Path.of("/jdk" + v)))).isEmpty();
        assertThat(GradleJdk.choose(Optional.empty(), 21, v -> Optional.of(Path.of("/jdk" + v)))).isEmpty();
    }

    @Test
    void picksTheNewestInstalledCompatibleJdkWhenTheCurrentOneIsTooNew() {
        Map<Integer, Path> installed = Map.of(11, Path.of("/jdk11"), 8, Path.of("/jdk8"));

        assertThat(GradleJdk.choose(Optional.of("6.9.1"), 17, v -> Optional.ofNullable(installed.get(v))))
                .hasValue(Path.of("/jdk11"));
        assertThat(GradleJdk.choose(Optional.of("6.9.1"), 17, v -> Optional.empty())).isEmpty();
    }
}
