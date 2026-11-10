package io.themis.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileCachePermissionsTest {

    @TempDir
    Path tmp;

    @Test
    void cacheDirectoriesAndFilesAreReadableByTheOwnerOnly() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path root = tmp.resolve("themis-cache");

        new FileCache(root, Clock.systemUTC()).put("osv-query", "org.vendor:lib:1.0", "value");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root))).isEqualTo("rwx------");
        try (var files = Files.walk(root)) {
            files.forEach(p -> {
                try {
                    String perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(p));
                    assertThat(perms).as(p.toString()).isEqualTo(Files.isDirectory(p) ? "rwx------" : "rw-------");
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }
}
