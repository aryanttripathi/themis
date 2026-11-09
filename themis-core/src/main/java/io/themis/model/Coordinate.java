package io.themis.model;

import java.util.Objects;

/**
 * Maven artifact coordinate. Most artifacts are plain jars, written {@code groupId:artifactId:version};
 * others carry their extension and classifier, written like Maven Resolver does:
 * {@code g:a:extension:version} or {@code g:a:extension:classifier:version}
 * (e.g. {@code io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.114.Final}).
 *
 * @param extension  file extension ({@code jar}, {@code pom}, {@code zip}, ...); a {@code test-jar}
 *                   dependency is extension {@code jar} with classifier {@code tests}
 * @param classifier empty for the main artifact
 */
public record Coordinate(String groupId, String artifactId, String version, String extension, String classifier) {

    public static final String DEFAULT_EXTENSION = "jar";

    public Coordinate {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(artifactId, "artifactId");
        Objects.requireNonNull(version, "version");
        extension = extension == null || extension.isEmpty() ? DEFAULT_EXTENSION : extension;
        classifier = classifier == null ? "" : classifier;
    }

    /** A plain jar {@code groupId:artifactId:version}. */
    public Coordinate(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, DEFAULT_EXTENSION, "");
    }

    /** Parses the {@link #toString()} forms: {@code g:a:v}, {@code g:a:ext:v}, {@code g:a:ext:classifier:v}. */
    public static Coordinate parse(String text) {
        String[] p = text.split(":");
        return switch (p.length) {
            case 3 -> new Coordinate(p[0], p[1], p[2]);
            case 4 -> new Coordinate(p[0], p[1], p[3], p[2], "");
            case 5 -> new Coordinate(p[0], p[1], p[4], p[2], p[3]);
            default -> throw new IllegalArgumentException(
                    "Expected groupId:artifactId[:extension[:classifier]]:version but got: " + text);
        };
    }

    /** {@code groupId:artifactId}, i.e. the package identity without version. */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /** {@code groupId:artifactId:extension:classifier}: the artifact identity without version. */
    public String artifactKey() {
        return groupId + ":" + artifactId + ":" + extension + ":" + classifier;
    }

    @Override
    public String toString() {
        if (!classifier.isEmpty()) return groupId + ":" + artifactId + ":" + extension + ":" + classifier + ":" + version;
        if (!DEFAULT_EXTENSION.equals(extension)) return groupId + ":" + artifactId + ":" + extension + ":" + version;
        return groupId + ":" + artifactId + ":" + version;
    }
}
