package io.themis.report;

import io.themis.model.Coordinate;
import io.themis.model.Dependency;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;
import io.themis.model.ScanReport;
import io.themis.model.Scope;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReportWriterTest {

    @Test
    void reportCarriesResolutionWarningsAndClassifiedCoordinates() throws Exception {
        Coordinate root = Coordinate.parse("g:app:1");
        Coordinate epoll = Coordinate.parse("io.netty:epoll:jar:linux-x86_64:4.1");
        DependencyGraph graph = new DependencyGraph(root,
                List.of(new Dependency(epoll, Scope.COMPILE, true, List.of(root, epoll), Optional.empty())),
                List.of("The POM for x:y:jar:1 is missing, no dependency information available"));
        ScanReport report = new ScanReport("test", Instant.EPOCH, Duration.ZERO,
                new ProjectInfo(Path.of("/p"), root, 17, List.of(), List.of()), graph, List.of(), List.of());

        String json = new JsonReportWriter().toJson(report);

        assertThat(json).contains("\"warnings\" : [ \"The POM for x:y:jar:1 is missing, no dependency information available\" ]");
        assertThat(json).contains("\"coordinate\" : \"io.netty:epoll:jar:linux-x86_64:4.1\"");
        assertThat(json).contains("\"modules\" : [ \"g:app:1\" ]");
    }
}
