package io.themis.privacy;

import io.themis.model.Coordinate;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LookupPrivacyTest {

    private static final Coordinate PROJECT = Coordinate.parse("com.acme.reviews:review-service:0.0.1-SNAPSHOT");

    @Test
    void theProjectsOwnOrganisationGroupIsPrivateByDefault() {
        LookupPrivacy p = LookupPrivacy.of(PrivacyConfig.defaults(), PROJECT);

        assertThat(p.isPublic(Coordinate.parse("com.acme:alerting-starter:0.1.5"))).isFalse();
        assertThat(p.isPublic(Coordinate.parse("com.acme.platform:inventory-client:0.0.6"))).isFalse();
        assertThat(p.isPublic(Coordinate.parse("com.acmex:other:1.0"))).isTrue();   // "com.acme" + segment boundary
        assertThat(p.isPublic(Coordinate.parse("org.springframework:spring-core:6.0.7"))).isTrue();
    }

    @Test
    void snapshotsAndTimestampedSnapshotsAreNeverSent() {
        LookupPrivacy p = LookupPrivacy.of(PrivacyConfig.defaults(), Coordinate.parse("org.acme:app:1"));

        assertThat(p.isPublic(Coordinate.parse("org.vendor:lib:1.0-SNAPSHOT"))).isFalse();
        assertThat(p.isPublic(Coordinate.parse("org.vendor:lib:2.9.5.1-20230310.055002-1"))).isFalse();
        assertThat(p.isPublic(Coordinate.parse("org.vendor:lib:1.0"))).isTrue();
    }

    @Test
    void configuredPatternsAreAddedAndTheProjectGroupRuleCanBeTurnedOff() {
        PrivacyConfig cfg = new PrivacyConfig(Set.of("io.vendor.internal:*"), false);
        LookupPrivacy p = LookupPrivacy.of(cfg, PROJECT);

        assertThat(p.isPublic(Coordinate.parse("io.vendor.internal:secret:1.0"))).isFalse();
        assertThat(p.isPublic(Coordinate.parse("com.acme:open-sourced:1.0"))).isTrue();
    }

    @Test
    void singleSegmentOrUnspecifiedGroupsAddNoProjectRule() {
        assertThat(LookupPrivacy.projectGroupPattern("unspecified")).isEmpty();
        assertThat(LookupPrivacy.projectGroupPattern("acme")).hasValue("acme");
        assertThat(LookupPrivacy.projectGroupPattern("com.acme.reviews")).hasValue("com.acme");
    }
}
