package io.themis.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.themis.Themis;
import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.Location;
import io.themis.model.ScanReport;
import io.themis.model.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * SARIF 2.1.0 ({@code themis.sarif}) for GitHub / GitLab code scanning.
 * <ul>
 *   <li>Secrets and PII: the exact file, line and column.</li>
 *   <li>Dependency findings: the line declaring the dependency in pom.xml / build.gradle[.kts]; for a
 *       transitive dependency, the line of the direct dependency that pulls it in (else line 1).</li>
 *   <li>{@code level}: CRITICAL/HIGH = error, MEDIUM = warning, LOW/INFO = note; rules carry
 *       {@code security-severity} (CVSS where known) so GitHub shows Critical/High/Medium/Low.</li>
 *   <li>{@code partialFingerprints} from the stable finding id, so alerts do not duplicate across runs.</li>
 * </ul>
 */
public final class SarifReportWriter implements ReportWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle.kts", "build.gradle");

    @Override
    public String format() {
        return "sarif";
    }

    @Override
    public Path write(ScanReport report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("themis.sarif");
        Files.writeString(out, MAPPER.writeValueAsString(document(report)), StandardCharsets.UTF_8);
        return out;
    }

    private Map<String, Object> document(ScanReport r) throws IOException {
        Path root = r.project().rootDir();
        Optional<Path> buildFile = BUILD_FILES.stream().map(root::resolve).filter(Files::isRegularFile).findFirst();
        List<String> buildLines = buildFile.isPresent() ? Files.readAllLines(buildFile.get()) : List.of();
        Map<String, Dependency> deps = new LinkedHashMap<>();
        r.graph().dependencies().forEach(d -> deps.putIfAbsent(d.coordinate().ga(), d));

        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Finding f : r.findings()) {
            String ruleId = ruleId(f);
            rules.computeIfAbsent(ruleId, id -> rule(id, f));
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("ruleId", ruleId);
            res.put("level", level(f.severity()));
            res.put("message", Map.of("text", f.detail().isBlank() ? f.title() : f.title() + ". " + f.detail()));
            location(f, root, buildFile, buildLines, deps).ifPresent(l -> res.put("locations", List.of(l)));
            res.put("partialFingerprints", Map.of("themisFindingId/v1", sha256(f.id())));
            res.put("properties", Map.of("themisSeverity", f.severity().name(), "themisType", f.type().name()));
            results.add(res);
        }

        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "Themis");
        driver.put("version", Themis.VERSION);
        driver.put("rules", new ArrayList<>(rules.values()));
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("results", results);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        doc.put("version", "2.1.0");
        doc.put("runs", List.of(run));
        return doc;
    }

    static String ruleId(Finding f) {
        String[] parts = f.id().split(":");
        String rule = f.evidence().getOrDefault("rule", parts.length > 1 ? parts[1] : f.type().name());
        return switch (f.type()) {
            case VULNERABILITY -> parts.length > 1 ? parts[1] : "vulnerability";
            case LICENSE -> "license/" + (parts.length > 1 ? parts[1] : "unknown").toLowerCase(Locale.ROOT).replace('_', '-');
            case SECRET -> "secret/" + rule;
            case PII -> "pii/" + rule;
            case JAVA_VERSION -> "java-version";
            case UPGRADE -> "upgrade";
        };
    }

    private static Map<String, Object> rule(String id, Finding f) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("name", id.replaceAll("[^A-Za-z0-9]+", "-"));
        rule.put("shortDescription", Map.of("text", shortDescription(f)));
        rule.put("defaultConfiguration", Map.of("level", level(f.severity())));
        String url = f.evidence().get("url");
        if (url != null && url.startsWith("https://")) rule.put("helpUri", url);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tags", List.of("security", f.type().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        props.put("security-severity", f.evidence().getOrDefault("cvss", securitySeverity(f.severity())));
        rule.put("properties", props);
        return rule;
    }

    private static String shortDescription(Finding f) {
        return switch (f.type()) {
            case VULNERABILITY -> "Known vulnerability in a dependency";
            case LICENSE -> "Dependency license obligation";
            case SECRET -> "Hardcoded secret";
            case PII -> "Personal data exposure";
            case JAVA_VERSION -> "Dependency requires a newer Java release";
            case UPGRADE -> "Dependency upgrade advice";
        };
    }

    private static Optional<Map<String, Object>> location(Finding f, Path root, Optional<Path> buildFile, List<String> buildLines,
                                                          Map<String, Dependency> deps) {
        if (f.location().isPresent()) {
            Location l = f.location().get();
            return Optional.of(physical(l.file().toString().replace('\\', '/'), l.line(), l.column()));
        }
        if (f.dependency().isEmpty() || buildFile.isEmpty()) return Optional.empty();
        Coordinate c = f.dependency().get();
        Dependency d = deps.get(c.ga());
        Coordinate declared = d != null && !d.direct() && d.path().size() >= 3 ? d.path().get(1) : c;
        int line = 1;
        for (int i = 0; i < buildLines.size(); i++) {
            String text = buildLines.get(i);
            if (text.contains("<artifactId>" + declared.artifactId() + "</artifactId>") || text.contains(declared.ga())
                    || (text.contains("'" + declared.groupId()) && text.contains(declared.artifactId()))) {
                line = i + 1;
                break;
            }
        }
        return Optional.of(physical(root.relativize(buildFile.get()).toString().replace('\\', '/'), line, 1));
    }

    private static Map<String, Object> physical(String uri, int line, int column) {
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("startLine", Math.max(1, line));
        region.put("startColumn", Math.max(1, column));
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("uri", uri);
        artifact.put("uriBaseId", "%SRCROOT%");
        return Map.of("physicalLocation", Map.of("artifactLocation", artifact, "region", region));
    }

    static String level(Severity s) {
        return switch (s) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW, INFO -> "note";
        };
    }

    private static String securitySeverity(Severity s) {
        return switch (s) {
            case CRITICAL -> "9.5";
            case HIGH -> "8.0";
            case MEDIUM -> "5.5";
            case LOW -> "3.0";
            case INFO -> "0.0";
        };
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
