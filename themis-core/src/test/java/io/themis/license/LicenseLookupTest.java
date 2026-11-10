package io.themis.license;

import io.themis.cache.FileCache;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.LicenseCategory;
import io.themis.model.LicenseInfo;
import io.themis.model.Scope;
import io.themis.resolve.DeclaredLicense;
import io.themis.resolve.DependencyMetadata;
import io.themis.resolve.PomLicenses;
import io.themis.testing.HttpStub;
import io.themis.testing.HttpStub.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseLookupTest {

    @TempDir
    Path tmp;

    private final HttpStub depsDev = HttpStub.start();
    private final Map<Coordinate, PomLicenses> poms = new HashMap<>();
    private final DependencyMetadata metadata = c -> poms.getOrDefault(c, PomLicenses.NONE);
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @AfterEach
    void stop() {
        depsDev.close();
    }

    @Test
    void configOverrideWinsOverEverything() {
        pom("com.acme:a:1.0", false, "GNU General Public License version 3", null);

        LicenseInfo info = lookup(Map.of("com.acme:a", "MIT"), false, dep("com.acme:a:1.0"));

        assertThat(info.spdx()).contains("MIT");
        assertThat(info.category()).isEqualTo(LicenseCategory.PERMISSIVE);
        assertThat(info.source()).isEqualTo("config");
        assertThat(info.declared()).contains("GNU General Public License version 3"); // still shown
        assertThat(depsDev.requests()).isEmpty();
    }

    @Test
    void versionSpecificOverrideBeatsArtifactOverride() {
        LicenseInfo info = lookup(Map.of("com.acme:a", "MIT", "com.acme:a:1.0", "Apache-2.0"), false, dep("com.acme:a:1.0"));
        assertThat(info.spdx()).contains("Apache-2.0");
    }

    @Test
    void ownPomAndInheritedPomAreDistinguished() {
        pom("com.acme:own:1.0", false, "Apache License, Version 2.0", "https://www.apache.org/licenses/LICENSE-2.0");
        pom("com.acme:child:1.0", true, "MIT License", null);

        List<LicenseInfo> infos = lookupAll(Map.of(), false, dep("com.acme:own:1.0"), dep("com.acme:child:1.0"));

        assertThat(infos.get(0).source()).isEqualTo("pom");
        assertThat(infos.get(0).spdx()).contains("Apache-2.0");
        assertThat(infos.get(0).declared()).containsExactly(
                "Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0)");
        assertThat(infos.get(1).source()).isEqualTo("parent-pom");
        assertThat(infos.get(1).spdx()).contains("MIT");
        assertThat(depsDev.requests()).isEmpty();
    }

    @Test
    void multiplePomLicensesAreDualLicensingCombinedWithOr() {
        poms.put(Coordinate.parse("ch.qos:logback:1.5"), new PomLicenses(List.of(
                new DeclaredLicense("Eclipse Public License - v 1.0", null),
                new DeclaredLicense("GNU Lesser General Public License", null)), false, Optional.empty()));

        LicenseInfo info = lookup(Map.of(), false, dep("ch.qos:logback:1.5"));

        assertThat(info.spdx()).contains("EPL-1.0 OR LGPL-2.1-only");
        assertThat(info.category()).isEqualTo(LicenseCategory.WEAK_COPYLEFT);
        assertThat(info.declared()).hasSize(2);
        assertThat(info.notes()).anySatisfy(n -> assertThat(n).contains("GNU Lesser General Public License").contains("LGPL-2.1-only"));
    }

    @Test
    void unrecognisedPomLicenseFallsThroughToDepsDevKeepingTheRawName() {
        pom("com.acme:a:1.0", false, "Acme Open License", null);
        depsDev.depsDev("com.acme:a:1.0", "BSD-3-Clause");

        LicenseInfo info = lookup(Map.of(), false, dep("com.acme:a:1.0"));

        assertThat(info.source()).isEqualTo("deps.dev");
        assertThat(info.spdx()).contains("BSD-3-Clause");
        assertThat(info.declared()).contains("Acme Open License");
    }

    @Test
    void depsDevAnswersAreCachedAndOfflineUsesOnlyTheCache() {
        depsDev.depsDev("com.acme:a:1.0", "Apache-2.0");

        assertThat(lookup(Map.of(), false, dep("com.acme:a:1.0")).spdx()).contains("Apache-2.0");
        assertThat(lookup(Map.of(), false, dep("com.acme:a:1.0")).spdx()).contains("Apache-2.0");
        assertThat(depsDev.requests()).hasSize(1); // second answer came from the cache

        assertThat(lookup(Map.of(), true, dep("com.acme:a:1.0")).source()).isEqualTo("deps.dev"); // offline, cached
        LicenseInfo uncached = lookup(Map.of(), true, dep("com.acme:b:1.0"));
        assertThat(uncached.category()).isEqualTo(LicenseCategory.UNKNOWN);
        assertThat(depsDev.requests()).hasSize(1); // offline: never on the wire

        now = now.plus(Duration.ofDays(31)); // past the 30-day license TTL
        lookup(Map.of(), false, dep("com.acme:a:1.0"));
        assertThat(depsDev.requests()).hasSize(2);
    }

    @Test
    void jarTextIsTheLastResort() throws IOException {
        Path jar = jar("META-INF/LICENSE", LicenseTextMatcherTest.MIT);

        LicenseInfo info = lookup(Map.of(), false,
                new Dependency(Coordinate.parse("com.acme:a:1.0"), Scope.COMPILE, true, List.of(), Optional.of(jar)));

        assertThat(info.source()).isEqualTo("jar:META-INF/LICENSE");
        assertThat(info.spdx()).contains("MIT");
        assertThat(depsDev.requests()).hasSize(1); // deps.dev was asked first and answered 404
    }

    @Test
    void nothingFoundIsUnknownWithRawTextAndProblemsKept() {
        pom("com.acme:a:1.0", false, "Acme Corp Internal License", "https://acme.example/eula");
        depsDev.on(HttpStub.depsDevPath("com.acme", "a", "1.0"), new Response(500, ""));

        LicenseInfo info = lookup(Map.of(), false, dep("com.acme:a:1.0"));

        assertThat(info.category()).isEqualTo(LicenseCategory.UNKNOWN);
        assertThat(info.spdx()).isEmpty();
        assertThat(info.source()).isEqualTo("none");
        assertThat(info.declared()).containsExactly("Acme Corp Internal License (https://acme.example/eula)");
        assertThat(info.notes()).anySatisfy(n -> assertThat(n).contains("deps.dev").contains("500"));
    }

    @Test
    void depsDevNonStandardIsNotARecognisedLicense() {
        depsDev.depsDev("com.acme:a:1.0", "non-standard");

        LicenseInfo info = lookup(Map.of(), false, dep("com.acme:a:1.0"));

        assertThat(info.category()).isEqualTo(LicenseCategory.UNKNOWN);
        assertThat(info.declared()).contains("non-standard (deps.dev)");
    }

    // ---------------------------------------------------------------------------------------

    private LicenseInfo lookup(Map<String, String> overrides, boolean offline, Dependency d) {
        return lookupAll(overrides, offline, d).get(0);
    }

    private List<LicenseInfo> lookupAll(Map<String, String> overrides, boolean offline, Dependency... ds) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        LicenseLookup lookup = new LicenseLookup(SpdxNormalizer.bundled(), new LicenseClassifier(),
                DepsDevClient.create(depsDev.baseUrl(), Duration.ofMillis(1)),
                new FileCache(tmp.resolve("cache"), clock), new LicenseTextMatcher());
        return lookup.lookupAll(List.of(ds), metadata,
                new LicenseLookup.Settings(overrides, offline, LicenseLookup.DEFAULT_TTL));
    }

    private void pom(String gav, boolean inherited, String name, String url) {
        poms.put(Coordinate.parse(gav), new PomLicenses(List.of(new DeclaredLicense(name, url)), inherited, Optional.empty()));
    }

    private static Dependency dep(String gav) {
        return new Dependency(Coordinate.parse(gav), Scope.COMPILE, true, List.of(), Optional.empty());
    }

    private Path jar(String entry, String text) throws IOException {
        Path jar = Files.createTempFile(tmp, "lib", ".jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os)) {
            out.putNextEntry(new JarEntry(entry));
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }
}
