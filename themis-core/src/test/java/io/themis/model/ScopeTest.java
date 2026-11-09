package io.themis.model;

import io.themis.testing.Locales;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeTest {

    @Test
    void mavenNamesRoundTripUnderTurkishLocale() {
        Locales.with(Locales.TURKISH, () -> {
            for (Scope s : Scope.values()) {
                assertThat(Scope.fromMaven(s.mavenName())).isEqualTo(s);
            }
            assertThat(Scope.COMPILE.mavenName()).isEqualTo("compile"); // not "compıle"
            assertThat(Scope.fromMaven("compile")).isEqualTo(Scope.COMPILE); // not "COMPİLE"
            return null;
        });
        assertThat(Scope.fromMaven("")).isEqualTo(Scope.COMPILE);
        assertThat(Scope.fromMaven(null)).isEqualTo(Scope.COMPILE);
    }

    @Test
    void widestOrdersCompileRuntimeProvidedSystemTest() {
        assertThat(Scope.widest(Scope.RUNTIME, Scope.COMPILE)).isEqualTo(Scope.COMPILE);
        assertThat(Scope.widest(Scope.PROVIDED, Scope.RUNTIME)).isEqualTo(Scope.RUNTIME);
        assertThat(Scope.widest(Scope.SYSTEM, Scope.PROVIDED)).isEqualTo(Scope.PROVIDED);
        assertThat(Scope.widest(Scope.TEST, Scope.SYSTEM)).isEqualTo(Scope.SYSTEM);
        assertThat(Scope.widest(Scope.TEST, Scope.TEST)).isEqualTo(Scope.TEST);
    }
}
