package io.themis.engine;

import io.themis.config.ScanConfig;
import io.themis.model.DependencyGraph;
import io.themis.model.ProjectInfo;
import io.themis.resolve.DependencyMetadata;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared, read-mostly state for one scan. */
public final class ScanContext {

    private final ProjectInfo project;
    private final DependencyGraph graph;
    private final ScanConfig config;
    private final DependencyMetadata metadata;
    private final List<Object> data = new CopyOnWriteArrayList<>();

    public ScanContext(ProjectInfo project, DependencyGraph graph, ScanConfig config) {
        this(project, graph, config, DependencyMetadata.NONE);
    }

    public ScanContext(ProjectInfo project, DependencyGraph graph, ScanConfig config, DependencyMetadata metadata) {
        this.project = project;
        this.graph = graph;
        this.config = config;
        this.metadata = metadata;
    }

    /** Build-tool metadata (e.g. dependency POM licenses), valid while the scan runs. */
    public DependencyMetadata metadata() {
        return metadata;
    }

    public ProjectInfo project() {
        return project;
    }

    public DependencyGraph graph() {
        return graph;
    }

    public ScanConfig config() {
        return config;
    }

    /** Typed outputs published by analyzers of earlier stages. */
    public <T> List<T> resultsOf(Class<T> type) {
        return data.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Seeds earlier-stage results, for testing ADVISOR analyzers in isolation. */
    public void publishForTest(List<Object> items) {
        publish(items);
    }

    void publish(List<Object> items) {
        data.addAll(items);
    }

    List<Object> allData() {
        return List.copyOf(data);
    }
}
