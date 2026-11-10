package io.themis.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FileCacheTest {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    @TempDir
    Path dir;

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };

    @Test
    void roundTripsValuesWithinTtlAndExpiresAfter() {
        FileCache cache = new FileCache(dir, clock);
        cache.put("license", "g:a:1", List.of("Apache-2.0"));

        assertThat(cache.get("license", "g:a:1", Duration.ofDays(30), STRINGS)).contains(List.of("Apache-2.0"));

        now.set(now.get().plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(cache.get("license", "g:a:1", Duration.ofDays(30), STRINGS)).isEmpty();
    }

    @Test
    void storesOneJsonFilePerKeyUnderSha256WithStoredAt() throws Exception {
        FileCache cache = new FileCache(dir, clock);
        cache.put("license", "g:a:1", List.of());

        // sha256("g:a:1")
        Path file = dir.resolve("license").resolve(FileCache.sha256("g:a:1") + ".json");
        assertThat(file).exists();
        assertThat(FileCache.sha256("g:a:1")).hasSize(64);
        assertThat(Files.readString(file)).contains("\"storedAt\"").contains("2026-01-01T00:00:00Z").contains("\"key\"");
        // an empty value is a valid cached answer (e.g. "deps.dev knows no license")
        assertThat(cache.get("license", "g:a:1", Duration.ofDays(1), STRINGS)).contains(List.of());
    }

    @Test
    void missesForUnknownKeyOtherNamespaceAndCorruptEntries() throws Exception {
        FileCache cache = new FileCache(dir, clock);
        cache.put("license", "g:a:1", List.of("MIT"));

        assertThat(cache.get("license", "g:a:2", Duration.ofDays(1), STRINGS)).isEmpty();
        assertThat(cache.get("osv-query", "g:a:1", Duration.ofDays(1), STRINGS)).isEmpty();

        Files.writeString(dir.resolve("license").resolve(FileCache.sha256("g:a:1") + ".json"), "{not json");
        assertThat(cache.get("license", "g:a:1", Duration.ofDays(1), STRINGS)).isEmpty();
    }
}
