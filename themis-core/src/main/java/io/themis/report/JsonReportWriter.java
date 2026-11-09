package io.themis.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.themis.model.Coordinate;
import io.themis.model.ScanReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Writes report.json (schemaVersion 1, see docs/LLD.md section 14). */
public final class JsonReportWriter implements ReportWriter {

    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule()
                    .addSerializer(Coordinate.class, ToStringSerializer.instance)
                    .addSerializer(Path.class, ToStringSerializer.instance))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public String format() {
        return "json";
    }

    @Override
    public Path write(ScanReport report, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path out = outputDir.resolve("report.json");
        mapper.writeValue(out.toFile(), toDocument(report));
        return out;
    }

    public String toJson(ScanReport report) throws IOException {
        return mapper.writeValueAsString(toDocument(report));
    }

    private Map<String, Object> toDocument(ScanReport r) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", SCHEMA_VERSION);
        doc.put("tool", Map.of("name", "themis", "version", r.toolVersion()));
        doc.put("startedAt", r.startedAt());
        doc.put("durationMillis", r.duration().toMillis());
        doc.put("project", Map.of(
                "coordinate", r.project().coordinate(),
                "javaRelease", r.project().javaRelease()));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dependencies", r.graph().dependencies().size());
        summary.put("direct", r.graph().directCount());
        summary.put("bySeverity", r.countBySeverity());
        doc.put("summary", summary);
        doc.put("dependencies", r.graph().dependencies());
        doc.put("findings", r.findings());
        doc.put("errors", r.errors());
        return doc;
    }
}
