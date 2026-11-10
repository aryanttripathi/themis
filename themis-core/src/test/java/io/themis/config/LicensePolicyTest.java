package io.themis.config;

import io.themis.model.Coordinate;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LicensePolicyTest {

    private static final Coordinate INVENTORY = Coordinate.parse("com.acme.platform:inventory-client:0.0.6");

    @Test
    void exactOverridesWinOverPatternsAndVersionedWinsOverUnversioned() {
        Map<String, String> o = Map.of(
                "com.acme*", "LicenseRef-Acme",
                "com.acme.platform:inventory-client", "MIT",
                "com.acme.platform:inventory-client:0.0.6", "Apache-2.0");

        assertThat(LicensePolicy.overrideFor(o, INVENTORY)).hasValue("Apache-2.0");
        assertThat(LicensePolicy.overrideFor(o, Coordinate.parse("com.acme.platform:inventory-client:0.0.7"))).hasValue("MIT");
        assertThat(LicensePolicy.overrideFor(o, Coordinate.parse("com.acme.messaging:event-bus:2.9"))).hasValue("LicenseRef-Acme");
        assertThat(LicensePolicy.overrideFor(o, Coordinate.parse("org.json:json:1"))).isEmpty();
    }

    @Test
    void theMostSpecificPatternWins() {
        Map<String, String> o = Map.of("com.acme*", "LicenseRef-Acme", "com.acme.platform:*-client", "MIT");

        assertThat(LicensePolicy.overrideFor(o, INVENTORY)).hasValue("MIT");
        assertThat(LicensePolicy.overrideFor(o, Coordinate.parse("com.acme.platform:core:1"))).hasValue("LicenseRef-Acme");
    }

    @Test
    void patternsAreLiteralApartFromStar() {
        Map<String, String> o = Map.of("com.acme*", "X");

        assertThat(LicensePolicy.overrideFor(o, Coordinate.parse("comXacme.evil:a:1"))).isEmpty();
    }

    @Test
    void allowArtifactsAcceptsPatternsToo() {
        LicensePolicy p = LicensePolicy.defaults().withAllowArtifacts(Set.of("com.mysql:*", "org.json:json"));

        assertThat(p.isAllowed("com.mysql:mysql-connector-j")).isTrue();
        assertThat(p.isAllowed("org.json:json")).isTrue();
        assertThat(p.isAllowed("mysql:mysql-connector-java")).isFalse();
    }
}
