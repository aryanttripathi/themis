package io.themis.suppress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.themis.config.ConfigException;
import io.themis.model.Finding;
import io.themis.model.ScanReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accepted findings at a point in time ({@code .themis-baseline.json}, meant to be committed): later
 * scans hide them and report only new ones. Holds ids, severities and titles only; titles of secret
 * findings carry no secret values (they never do), so the file is safe to commit.
 */
public record Baseline(Set<String> ids) {

    public static final String FILE_NAME = ".themis-baseline.json";
    public static final Baseline EMPTY = new Baseline(Set.of());
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public Baseline {
        ids = Set.copyOf(ids);
    }

    public static void write(Path file, ScanReport report) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", 1);
        doc.put("createdAt", report.startedAt().toString());
        doc.put("tool", "themis " + report.toolVersion());
        doc.put("project", report.project().coordinate().toString());
        List<Map<String, String>> entries = new ArrayList<>();
        for (Finding f : report.findings()) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("id", f.id());
            e.put("severity", f.severity().name());
            e.put("title", f.title());
            entries.add(e);
        }
        entries.sort((a, b) -> a.get("id").compareTo(b.get("id")));   // stable diffs in code review
        doc.put("findings", entries);
        Files.writeString(file, MAPPER.writeValueAsString(doc) + "\n");
    }

    public static Baseline read(Path file) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (root.path("schemaVersion").asInt() != 1) throw new ConfigException(file + ": unsupported baseline schemaVersion");
            Set<String> ids = new LinkedHashSet<>();
            root.path("findings").forEach(f -> ids.add(f.path("id").asText()));
            return new Baseline(ids);
        } catch (IOException e) {
            throw new ConfigException("Cannot read baseline " + file + ": " + e.getMessage(), e);
        }
    }

    public static Baseline readIfExists(Path file) {
        return Files.isRegularFile(file) ? read(file) : EMPTY;
    }
}
