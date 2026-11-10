package io.themis.config;

import io.themis.model.LicenseCategory;
import io.themis.model.Severity;
import io.themis.testing.Locales;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {

    @TempDir
    Path dir;

    @Test
    void readsEverySupportedKeyAndAppliesItOverDefaults() throws IOException {
        Path file = write(".themis.yml", """
                javaRelease: 21
                analyzers: [licenses]
                failOn:
                  severity: medium
                licenses:
                  severity:
                    NETWORK_COPYLEFT: CRITICAL
                    UNKNOWN: NONE
                  overrides:
                    "com.example:legacy-lib": "Apache-2.0"
                    "com.example:other:1.2": "LicenseRef-Acme-Commercial"
                  allowArtifacts: ["org.mariadb.jdbc:mariadb-java-client"]
                network:
                  offline: true
                  cacheTtlHours: 48
                """);

        ScanConfig c = ConfigLoader.read(file).applyTo(ScanConfig.defaults());

        assertThat(c.javaRelease()).isEqualTo(OptionalInt.of(21));
        assertThat(c.enabledAnalyzers()).containsExactly("licenses");
        assertThat(c.failOn()).isEqualTo(Severity.MEDIUM);
        assertThat(c.offline()).isTrue();
        assertThat(c.cacheTtlHours()).isEqualTo(OptionalInt.of(48));
        assertThat(c.licenses().severities())
                .containsEntry(LicenseCategory.NETWORK_COPYLEFT, Severity.CRITICAL)
                .containsEntry(LicenseCategory.STRONG_COPYLEFT, Severity.HIGH) // untouched default
                .doesNotContainKey(LicenseCategory.UNKNOWN);                   // NONE: no finding
        assertThat(c.licenses().overrides()).containsEntry("com.example:legacy-lib", "Apache-2.0")
                .containsEntry("com.example:other:1.2", "LicenseRef-Acme-Commercial");
        assertThat(c.licenses().allowArtifacts()).containsExactly("org.mariadb.jdbc:mariadb-java-client");
    }

    @Test
    void missingKeysKeepTheBase() throws IOException {
        ScanConfig base = ScanConfig.defaults().withFailOn(Severity.LOW);

        assertThat(ConfigLoader.read(write("a.yml", "")).applyTo(base)).isEqualTo(base);
        assertThat(ConfigLoader.read(write("b.yml", "licenses:\n  allowArtifacts: [\"g:a\"]\n")).applyTo(base).failOn())
                .isEqualTo(Severity.LOW);
    }

    @Test
    void unknownKeyIsAClearErrorNamingTheKeyAndLine() throws IOException {
        Path file = write(".themis.yml", """
                licenses:
                  overrides: {}
                  denyy: [NETWORK_COPYLEFT]
                """);

        assertThatThrownBy(() -> ConfigLoader.read(file))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("Unknown key 'licenses.denyy'")
                .hasMessageContaining(file.toString())
                .hasMessageContaining("line 3")
                .hasMessageContaining("allowArtifacts");
    }

    @Test
    void unknownTopLevelKeyIsAnError() throws IOException {
        assertThatThrownBy(() -> ConfigLoader.read(write("x.yml", "secretz:\n  exclude: []\n")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("Unknown key 'secretz'");
    }

    @Test
    void invalidValuesNameTheirKey() throws IOException {
        assertThatThrownBy(() -> ConfigLoader.read(write("1.yml", "failOn:\n  severity: SEVERE\n")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("failOn.severity").hasMessageContaining("SEVERE");
        assertThatThrownBy(() -> ConfigLoader.read(write("2.yml", "licenses:\n  severity:\n    COPYLEFT: HIGH\n")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("licenses.severity").hasMessageContaining("COPYLEFT");
        assertThatThrownBy(() -> ConfigLoader.read(write("3.yml", "licenses:\n  overrides:\n    \"g:a\": \"Acme Open\"\n")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("licenses.overrides").hasMessageContaining("Acme Open");
        assertThatThrownBy(() -> ConfigLoader.read(write("4.yml", "licenses:\n  overrides:\n    \"just-a-name\": MIT\n")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("groupId:artifactId");
        assertThatThrownBy(() -> ConfigLoader.read(write("5.yml", "javaRelease: seventeen\n")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("javaRelease");
        assertThatThrownBy(() -> ConfigLoader.read(write("6.yml", "licenses: [oops\n")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("6.yml");
    }

    @Test
    void overrideValuesAreNormalisedToSpdx() throws IOException {
        ScanConfig c = ConfigLoader.read(write("o.yml", "licenses:\n  overrides:\n    \"g:a\": \"The MIT License\"\n"))
                .applyTo(ScanConfig.defaults());
        assertThat(c.licenses().overrides()).containsEntry("g:a", "MIT");
    }

    @Test
    void discoversProjectFileUnlessAnExplicitOneIsGiven() throws IOException {
        Path project = Files.createDirectories(dir.resolve("p"));
        assertThat(ConfigLoader.load(project, Optional.empty())).isEmpty();

        Files.writeString(project.resolve(".themis.yml"), "javaRelease: 11\n");
        assertThat(ConfigLoader.load(project, Optional.empty())).hasValueSatisfying(f ->
                assertThat(f.applyTo(ScanConfig.defaults()).javaRelease()).isEqualTo(OptionalInt.of(11)));

        Path explicit = write("ci.yml", "javaRelease: 8\n");
        assertThat(ConfigLoader.load(project, Optional.of(explicit))).hasValueSatisfying(f ->
                assertThat(f.applyTo(ScanConfig.defaults()).javaRelease()).isEqualTo(OptionalInt.of(8)));

        assertThatThrownBy(() -> ConfigLoader.load(project, Optional.of(dir.resolve("nope.yml"))))
                .isInstanceOf(ConfigException.class).hasMessageContaining("nope.yml");
    }

    @Test
    void severityNamesAreCaseInsensitiveUnderTurkishLocale() throws IOException {
        Path file = write("t.yml", "failOn:\n  severity: high\nlicenses:\n  severity:\n    unknown: info\n");

        ScanConfig c = Locales.with(Locales.TURKISH, () -> ConfigLoader.read(file).applyTo(ScanConfig.defaults()));

        assertThat(c.failOn()).isEqualTo(Severity.HIGH);
        assertThat(c.licenses().severities()).containsEntry(LicenseCategory.UNKNOWN, Severity.INFO);
    }

    @Test
    void analyzersMustBeKnownNames() throws IOException {
        Path file = write("an.yml", "analyzers: [licences]\n");
        assertThatThrownBy(() -> ConfigLoader.read(file, Set.of("licenses", "javaversion")))
                .isInstanceOf(ConfigException.class).hasMessageContaining("licences").hasMessageContaining("javaversion");
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }
}
