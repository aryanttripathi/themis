package io.themis.report;

import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.LicenseCategory;
import io.themis.model.LicenseInfo;
import io.themis.model.ProjectInfo;
import io.themis.model.ScanReport;
import io.themis.model.Scope;
import io.themis.model.Severity;
import io.themis.upgrade.UpgradeSuggestion;
import io.themis.vuln.Advisory;
import io.themis.vuln.DependencyVulnerabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlReportWriterTest {

    @TempDir
    Path tmp;

    private static final Coordinate LIB = Coordinate.parse("org.vendor:lib:1.0");
    private static final String EVIL = "<script>alert('x')</script><img src=x onerror=alert(1)>";

    @Test
    void writesASelfContainedReportWithAllSections() throws Exception {
        Path html = new HtmlReportWriter().write(report(), tmp);

        String page = Files.readString(html);
        assertThat(html.getFileName().toString()).isEqualTo("report.html");
        assertThat(page).startsWith("<!DOCTYPE html>")
                .contains("com.acme:app:1.0")
                .contains("id=\"themis-data\"")
                .contains("org.vendor:lib:1.0")
                .contains("GHSA-test")
                .contains("Apache-2.0")
                .contains("2.0");   // upgrade recommendation
        for (String tab : List.of("Findings", "Vulnerabilities", "Upgrades", "Licenses", "Dependencies")) {
            assertThat(page).contains("data-tab=\"" + tab.toLowerCase() + "\"");
        }
    }

    @Test
    void loadsNothingFromTheNetworkAndForbidsItViaContentSecurityPolicy() throws Exception {
        String page = Files.readString(new HtmlReportWriter().write(report(), tmp));
        String template = page.substring(0, page.indexOf("id=\"themis-data\""));

        assertThat(page).contains("Content-Security-Policy").contains("default-src 'none'").contains("connect-src 'none'");
        assertThat(template).doesNotContainPattern("(src|href)=\"(https?:)?//").doesNotContain("<link rel=\"stylesheet\"")
                .doesNotContain("@import").doesNotContain("fetch(").doesNotContain("XMLHttpRequest");
    }

    @Test
    void untrustedTextCannotBreakOutOfTheDataBlock() throws Exception {
        String page = Files.readString(new HtmlReportWriter().write(report(), tmp));

        assertThat(page).doesNotContain(EVIL).doesNotContain("<script>alert").doesNotContain("<img src=x");
        assertThat(page).contains("\\u003cscript\\u003e");      // still present, as inert data
        assertThat(page).doesNotContain("innerHTML");            // rendering uses textContent only
    }

    private ScanReport report() {
        ProjectInfo project = new ProjectInfo(Path.of("/p"), Coordinate.parse("com.acme:app:1.0"), 17, List.of(), List.of());
        Dependency dep = new Dependency(LIB, Scope.COMPILE, true, List.of(project.coordinate(), LIB), Optional.empty());
        Advisory adv = new Advisory("GHSA-test", List.of("CVE-2024-1"), EVIL, Severity.CRITICAL, Optional.of(9.8),
                List.of("2.0"), Optional.of("2.0"), "https://osv.dev/vulnerability/GHSA-test");
        Finding f = new Finding("VULNERABILITY:GHSA-test:org.vendor:lib", FindingType.VULNERABILITY, Severity.CRITICAL,
                "org.vendor:lib:1.0 has GHSA-test: " + EVIL, "Fixed in 2.0.", Optional.of(LIB), Optional.empty(), Map.of("fixedIn", "2.0"));
        List<Object> data = List.of(
                new LicenseInfo(LIB, Optional.of("Apache-2.0"), LicenseCategory.PERMISSIVE, "pom", List.of("Apache 2"), List.of()),
                new DependencyVulnerabilities(LIB, List.of(adv), Optional.of("2.0"), Optional.empty()),
                new UpgradeSuggestion(LIB, Optional.of("2.0"), Optional.of(8), false, Optional.empty(), 1, ""));
        return new ScanReport("test", Instant.parse("2026-11-10T10:00:00Z"), Duration.ofMillis(5), project,
                new DependencyGraph(project.coordinate(), List.of(dep), List.of("a warning")), List.of(f), List.of(), data);
    }
}
