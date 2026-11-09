package io.themis.javaversion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.OptionalInt;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Reads the class-file format version from bytecode, which is the ground truth for the
 * minimum Java release a jar needs. Class file layout: u4 magic (0xCAFEBABE), u2 minor, u2 major.
 * Java release = major - 44 (52 = Java 8, 55 = 11, 61 = 17, 65 = 21).
 */
public final class ClassFileVersion {

    private static final int MAGIC = 0xCAFEBABE;
    private static final int RELEASE_OFFSET = 44;

    private ClassFileVersion() {
    }

    /** Result for one jar. {@code requiredRelease} is empty if the jar contains no classes. */
    public record JarVersion(OptionalInt requiredRelease, boolean multiRelease) {
    }

    /** Returns the class-file major version from the first 8 bytes, or empty if not a class file. */
    public static OptionalInt majorVersion(byte[] header) {
        if (header == null || header.length < 8) return OptionalInt.empty();
        int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        if (magic != MAGIC) return OptionalInt.empty();
        return OptionalInt.of(((header[6] & 0xFF) << 8) | (header[7] & 0xFF));
    }

    public static int toJavaRelease(int majorVersion) {
        return majorVersion - RELEASE_OFFSET;
    }

    /**
     * Scans every base-level class in the jar and returns the highest required release.
     * One class compiled for 21 makes the whole jar require 21. Versioned entries of a
     * multi-release jar (META-INF/versions/N/) are optional enhancements and are skipped.
     */
    public static JarVersion scanJar(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            boolean multiRelease = isMultiRelease(jf);
            int maxMajor = -1;
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".class")) continue;
                if (name.startsWith("META-INF/versions/") || name.endsWith("module-info.class")) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    OptionalInt major = majorVersion(in.readNBytes(8));
                    if (major.isPresent() && major.getAsInt() > maxMajor) maxMajor = major.getAsInt();
                }
            }
            OptionalInt release = maxMajor < 0 ? OptionalInt.empty() : OptionalInt.of(toJavaRelease(maxMajor));
            return new JarVersion(release, multiRelease);
        }
    }

    private static boolean isMultiRelease(JarFile jf) throws IOException {
        Manifest m = jf.getManifest();
        if (m == null) return false;
        return "true".equalsIgnoreCase(m.getMainAttributes().getValue(new Attributes.Name("Multi-Release")));
    }
}
