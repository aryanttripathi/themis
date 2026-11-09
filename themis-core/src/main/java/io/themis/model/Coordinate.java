package io.themis.model;

import java.util.Objects;

/** Maven coordinate {@code groupId:artifactId:version}. */
public record Coordinate(String groupId, String artifactId, String version) {

    public Coordinate {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
    }

    /** Parses {@code g:a:v}. */
    public static Coordinate parse(String gav) {
        String[] parts = gav.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Expected groupId:artifactId:version but got: " + gav);
        }
        return new Coordinate(parts[0], parts[1], parts[2]);
    }

    /** {@code groupId:artifactId}, i.e. the package identity without version. */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + version;
    }
}
