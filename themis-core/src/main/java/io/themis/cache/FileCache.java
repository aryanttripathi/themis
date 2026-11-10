package io.themis.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Small JSON file cache: {@code {root}/{namespace}/{sha256(key)}.json} holding
 * {@code {"storedAt": <ISO instant>, "key": <key>, "value": <value>}} (docs/LLD.md section 15).
 * The TTL is chosen by the reader, so one entry can serve callers with different freshness needs.
 * An unreadable or corrupt entry is a miss and is overwritten by the next {@link #put}.
 * Safe for concurrent use: entries are written to a temp file and moved into place.
 */
public final class FileCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path root;
    private final Clock clock;

    public FileCache(Path root, Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    /** {@code -Dthemis.cache.dir} if set, else {@code ~/.themis/cache}. */
    public static FileCache defaultCache() {
        return new FileCache(defaultDirectory(), Clock.systemUTC());
    }

    public static Path defaultDirectory() {
        String override = System.getProperty("themis.cache.dir");
        return override != null ? Path.of(override) : Path.of(System.getProperty("user.home"), ".themis", "cache");
    }

    public Path root() {
        return root;
    }

    /** The cached value if present and stored less than {@code ttl} ago. */
    public <T> Optional<T> get(String namespace, String key, Duration ttl, TypeReference<T> type) {
        Path file = file(namespace, key);
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read cache entry " + file, e);
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null || !key.equals(node.path("key").asText(null))) return Optional.empty(); // hash collision or foreign file
            Instant storedAt = Instant.parse(node.path("storedAt").asText());
            if (!storedAt.plus(ttl).isAfter(clock.instant())) return Optional.empty();
            return Optional.ofNullable(MAPPER.convertValue(node.get("value"), type));
        } catch (JsonProcessingException | DateTimeParseException | IllegalArgumentException e) {
            return Optional.empty(); // corrupt entry: a miss; put() replaces it
        }
    }

    public void put(String namespace, String key, Object value) {
        Path file = file(namespace, key);
        ObjectNode node = MAPPER.createObjectNode();
        node.put("storedAt", clock.instant().toString());
        node.put("key", key);
        node.set("value", MAPPER.valueToTree(value));
        try {
            createPrivateDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".entry", ".tmp");   // created rw------- on POSIX
            Files.writeString(tmp, MAPPER.writeValueAsString(node), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write cache entry " + file, e);
        }
    }

    /**
     * Creates missing directories from the cache root down, owner-only ({@code rwx------}) where the
     * file system supports POSIX permissions: the cache reveals which packages your projects use.
     */
    private void createPrivateDirectories(Path dir) throws IOException {
        boolean posix = dir.getFileSystem().supportedFileAttributeViews().contains("posix");
        java.util.Deque<Path> missing = new java.util.ArrayDeque<>();
        for (Path p = dir; p != null && !Files.isDirectory(p); p = p.getParent()) {
            missing.push(p);
            if (p.equals(root)) break;
        }
        for (Path p : missing) {
            if (posix && p.startsWith(root)) {
                Files.createDirectories(p, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(p);
            }
        }
        if (posix && Files.isDirectory(root)) {
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    private Path file(String namespace, String key) {
        return root.resolve(namespace).resolve(sha256(key) + ".json");
    }

    static String sha256(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
