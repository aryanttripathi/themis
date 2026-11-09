package io.themis.model;

import java.util.List;

/**
 * Flattened, de-duplicated view of the resolved dependency tree.
 *
 * @param dependencies one entry per distinct artifact and version
 * @param warnings     non-fatal resolution problems, worded like Maven's own warnings
 *                     (e.g. "The POM for g:a:jar:1 is missing, no dependency information available")
 */
public record DependencyGraph(Coordinate root, List<Dependency> dependencies, List<String> warnings) {

    public DependencyGraph {
        dependencies = List.copyOf(dependencies);
        warnings = List.copyOf(warnings);
    }

    public DependencyGraph(Coordinate root, List<Dependency> dependencies) {
        this(root, dependencies, List.of());
    }

    public static DependencyGraph empty(Coordinate root) {
        return new DependencyGraph(root, List.of());
    }

    public long directCount() {
        return dependencies.stream().filter(Dependency::direct).count();
    }
}
