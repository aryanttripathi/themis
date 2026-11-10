package io.themis.resolve.gradle;

import java.util.List;
import java.util.Map;

/** The JSON written by {@code themis-init.gradle}; plain data, mapped to the model by {@link GradleProjectSession}. */
record GradleDump(Coord root, List<Project> projects, List<Unresolved> unresolved, Map<String, Pom> licenses) {

    GradleDump {
        projects = projects == null ? List.of() : projects;
        unresolved = unresolved == null ? List.of() : unresolved;
        licenses = licenses == null ? Map.of() : licenses;
    }

    record Coord(String group, String name, String version) {
        String gav() {
            return (group == null ? "" : group) + ":" + name + ":" + (version == null ? "" : version);
        }
    }

    record Project(String path, Coord coordinate, List<String> sourceDirs, List<String> resourceDirs,
                   Integer javaRelease, Map<String, List<Node>> configurations) {
        Project {
            sourceDirs = sourceDirs == null ? List.of() : sourceDirs;
            resourceDirs = resourceDirs == null ? List.of() : resourceDirs;
            configurations = configurations == null ? Map.of() : configurations;
        }
    }

    record Node(String coordinate, boolean direct, List<String> path, List<String> files) {
        Node {
            path = path == null ? List.of() : path;
            files = files == null ? List.of() : files;
        }
    }

    record Unresolved(String project, String configuration, String requested, String reason) {
    }

    record License(String name, String url) {
    }

    record Pom(List<License> licenses, boolean inherited, String problem) {
        Pom {
            licenses = licenses == null ? List.of() : licenses;
        }
    }
}
