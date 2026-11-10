package io.themis.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.themis.javaversion.JavaRequirement;
import io.themis.model.Dependency;
import io.themis.model.Finding;
import io.themis.model.LicenseCategory;
import io.themis.model.LicenseInfo;
import io.themis.model.ScanReport;
import io.themis.model.Severity;
import io.themis.upgrade.UpgradeSuggestion;
import io.themis.vuln.Advisory;
import io.themis.vuln.DependencyVulnerabilities;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes {@code report.html}: one self-contained page (inline CSS/JS, no CDN, fonts or analytics)
 * with summary cards and searchable, sortable tables for findings, vulnerabilities, upgrades,
 * licenses and dependencies.
 *
 * <p>Safety: report data comes partly from public advisory databases, so it is untrusted. It is
 * embedded as JSON inside {@code <script type="application/json">} with {@code < > &} and line
 * separators escaped (cannot close the tag), rendered only through DOM text nodes, links only for
 * {@code https://} URLs, and a Content-Security-Policy forbids any network access from the page.
 */
public final class HtmlReportWriter implements ReportWriter {

    private static final String TEMPLATE = "report.html";
    private static final String PLACEHOLDER = "/*THEMIS_DATA*/";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    @Override
    public String format() {
        return "html";
    }

    @Override
    public Path write(ScanReport report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("report.html");
        Files.writeString(out, render(report), StandardCharsets.UTF_8);
        return out;
    }

    public String render(ScanReport report) throws IOException {
        String template;
        try (InputStream in = HtmlReportWriter.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IllegalStateException("missing resource " + TEMPLATE);
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return template.replace(PLACEHOLDER, scriptSafe(MAPPER.writeValueAsString(model(report))));
    }

    /** JSON that cannot terminate the surrounding script element or be read as markup. */
    static String scriptSafe(String json) {
        return json.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static Map<String, Object> model(ScanReport r) {
        Map<String, LicenseInfo> licenses = new HashMap<>();
        r.dataOf(LicenseInfo.class).forEach(l -> licenses.put(l.dependency().toString(), l));
        Map<String, DependencyVulnerabilities> vulns = new HashMap<>();
        r.dataOf(DependencyVulnerabilities.class).forEach(v -> vulns.put(gav(v.dependency().toString()), v));
        Map<String, Integer> java = new HashMap<>();
        r.dataOf(JavaRequirement.class).forEach(j -> java.put(j.dependency().toString(), j.requiredRelease()));
        Map<String, Dependency> deps = new LinkedHashMap<>();
        r.graph().dependencies().forEach(d -> deps.putIfAbsent(d.coordinate().toString(), d));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", r.toolVersion());
        m.put("generatedAt", r.startedAt().toString());
        m.put("project", Map.of("coordinate", r.project().coordinate().toString(), "javaRelease", r.project().javaRelease()));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dependencies", r.graph().dependencies().size());
        summary.put("direct", r.graph().directCount());
        Map<String, Long> bySeverity = new LinkedHashMap<>();
        r.countBySeverity().forEach((k, v) -> bySeverity.put(k.name(), v));
        summary.put("bySeverity", bySeverity);
        Map<LicenseCategory, Long> cats = new EnumMap<>(LicenseCategory.class);
        licenses.values().forEach(l -> cats.merge(l.category(), 1L, Long::sum));
        Map<String, Long> catNames = new LinkedHashMap<>();
        cats.forEach((k, v) -> catNames.put(k.name(), v));
        summary.put("licenses", catNames);
        summary.put("vulnerableDependencies", vulns.values().stream().filter(v -> !v.advisories().isEmpty()).count());
        summary.put("advisories", vulns.values().stream().mapToLong(v -> v.advisories().size()).sum());
        summary.put("notChecked", vulns.values().stream().filter(v -> v.notChecked().isPresent()).count());
        summary.put("upgrades", r.dataOf(UpgradeSuggestion.class).stream().filter(u -> u.recommended().isPresent()).count());
        m.put("summary", summary);

        List<Map<String, Object>> findings = new ArrayList<>();
        for (Finding f : r.findings()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", f.id());
            x.put("severity", f.severity().name());
            x.put("type", f.type().name());
            x.put("title", f.title());
            x.put("detail", f.detail());
            x.put("dependency", f.dependency().map(Object::toString).orElse(""));
            x.put("location", f.location().map(l -> l.file().toString().replace('\\', '/') + ":" + l.line()).orElse(""));
            x.put("evidence", f.evidence());
            findings.add(x);
        }
        m.put("findings", findings);

        List<Map<String, Object>> vulnRows = new ArrayList<>();
        for (DependencyVulnerabilities v : r.dataOf(DependencyVulnerabilities.class)) {
            if (v.advisories().isEmpty()) continue;
            Dependency d = deps.get(v.dependency().toString());
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("dependency", v.dependency().toString());
            x.put("scope", d == null ? "" : d.scope().mavenName());
            x.put("direct", d != null && d.direct());
            x.put("fixedIn", v.fixedIn().orElse(null));
            List<Map<String, Object>> advisories = new ArrayList<>();
            for (Advisory a : v.advisories()) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("id", a.id());
                y.put("aliases", a.aliases());
                y.put("summary", a.summary());
                y.put("severity", a.severity().name());
                y.put("cvss", a.cvss().orElse(null));
                y.put("fixedIn", a.fixedIn().orElse(null));
                y.put("fixedVersions", a.fixedVersions());
                y.put("url", a.url());
                advisories.add(y);
            }
            x.put("advisories", advisories);
            vulnRows.add(x);
        }
        m.put("vulnerabilities", vulnRows);

        List<Map<String, Object>> upgrades = new ArrayList<>();
        for (UpgradeSuggestion u : r.dataOf(UpgradeSuggestion.class)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("dependency", u.current().toString());
            x.put("recommended", u.recommended().orElse(null));
            x.put("requiredJava", u.requiredJavaRelease().orElse(null));
            x.put("sameMajor", u.sameMajor());
            x.put("viaDirect", u.viaDirect().map(Object::toString).orElse(null));
            x.put("fixes", u.fixes());
            x.put("note", u.note());
            upgrades.add(x);
        }
        m.put("upgrades", upgrades);

        List<Map<String, Object>> licenseRows = new ArrayList<>();
        for (LicenseInfo l : r.dataOf(LicenseInfo.class)) {
            Dependency d = deps.get(l.dependency().toString());
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("dependency", l.dependency().toString());
            x.put("scope", d == null ? "" : d.scope().mavenName());
            x.put("spdx", l.spdx().orElse(null));
            x.put("category", l.category().name());
            x.put("source", l.source());
            x.put("declared", l.declared());
            x.put("notes", l.notes());
            licenseRows.add(x);
        }
        m.put("licenses", licenseRows);

        List<Map<String, Object>> depRows = new ArrayList<>();
        for (Dependency d : deps.values()) {
            String key = d.coordinate().toString();
            LicenseInfo l = licenses.get(key);
            DependencyVulnerabilities v = vulns.get(gav(key));
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("coordinate", key);
            x.put("scope", d.scope().mavenName());
            x.put("direct", d.direct());
            x.put("path", d.path().stream().map(Object::toString).toList());
            x.put("modules", d.modules().stream().map(Object::toString).toList());
            x.put("spdx", l == null ? null : l.spdx().orElse(null));
            x.put("category", l == null ? null : l.category().name());
            x.put("advisories", v == null ? 0 : v.advisories().size());
            x.put("worst", v == null ? null : v.advisories().stream().map(Advisory::severity)
                    .max(Comparator.naturalOrder()).map(Severity::name).orElse(null));
            x.put("requiredJava", java.get(key));
            x.put("jar", d.jarFile().map(p -> home(p.toString())).orElse(null));
            depRows.add(x);
        }
        m.put("dependencies", depRows);
        m.put("warnings", r.graph().warnings());
        m.put("suppressed", r.suppressed().stream().map(x -> Map.of("id", x.finding().id(), "title", x.finding().title(),
                "severity", x.finding().severity().name(), "type", x.finding().type().name(), "reason", x.reason())).toList());
        m.put("errors", r.errors().stream().map(e -> Map.of("analyzer", e.analyzer(), "message", e.message())).toList());
        return m;
    }

    /** g:a:v of a coordinate string that may carry an extension/classifier. */
    private static String gav(String coordinate) {
        String[] p = coordinate.split(":");
        return p.length <= 3 ? coordinate : p[0] + ":" + p[1] + ":" + p[p.length - 1];
    }

    /** Paths under the user's home shown as {@code ~/...}, so a shared report does not reveal the username. */
    static String home(String path) {
        String home = System.getProperty("user.home");
        return home != null && !home.isEmpty() && path.startsWith(home) ? "~" + path.substring(home.length()) : path;
    }
}
