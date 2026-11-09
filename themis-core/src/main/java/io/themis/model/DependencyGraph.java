package io.themis.model;

import java.util.List;

/** Flattened, de-duplicated view of the resolved dependency tree. */
public record DependencyGraph(Coordinate root, List<Dependency> dependencies) {

    public DependencyGraph {
        dependencies = List.copyOf(dependencies);
    }

    public static DependencyGraph empty(Coordinate root) {
        return new DependencyGraph(root, List.of());
    }

    public long directCount() {
        return dependencies.stream().filter(Dependency::direct).count();
    }
}
