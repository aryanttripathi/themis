package io.themis.javaversion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;

class ClassFileVersionTest {

    @TempDir
    Path tmp;

    /** Minimal class-file header: magic, minor=0, major. */
    static byte[] header(int major) {
        return new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, (byte) (major >> 8), (byte) major};
    }

    @Test
    void readsMajorVersionFromHeader() {
        assertThat(ClassFileVersion.majorVersion(header(61))).hasValue(61);
        assertThat(ClassFileVersion.toJavaRelease(61)).isEqualTo(17);
        assertThat(ClassFileVersion.toJavaRelease(52)).isEqualTo(8);
    }

    @Test
    void rejectsNonClassBytes() {
        assertThat(ClassFileVersion.majorVersion(new byte[]{1, 2, 3, 4, 5, 6, 7, 8})).isEmpty();
        assertThat(ClassFileVersion.majorVersion(new byte[]{1, 2})).isEmpty();
    }

    @Test
    void jarRequiresHighestClassVersion() throws IOException {
        Path jar = jar("mixed.jar", false,
                "a/Old.class", header(52),
                "a/New.class", header(65));

        ClassFileVersion.JarVersion v = ClassFileVersion.scanJar(jar);

        assertThat(v.requiredRelease()).hasValue(21);
        assertThat(v.multiRelease()).isFalse();
    }

    @Test
    void multiReleaseJarUsesBaseVersionOnly() throws IOException {
        Path jar = jar("mr.jar", true,
                "a/Base.class", header(52),
                "META-INF/versions/21/a/Base.class", header(65),
                "module-info.class", header(53));

        ClassFileVersion.JarVersion v = ClassFileVersion.scanJar(jar);

        assertThat(v.requiredRelease()).hasValue(8);
        assertThat(v.multiRelease()).isTrue();
    }

    private Path jar(String name, boolean multiRelease, Object... entries) throws IOException {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (multiRelease) mf.getMainAttributes().put(new Attributes.Name("Multi-Release"), "true");

        Path jar = tmp.resolve(name);
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(os, mf)) {
            for (int i = 0; i < entries.length; i += 2) {
                jos.putNextEntry(new JarEntry((String) entries[i]));
                jos.write((byte[]) entries[i + 1]);
                jos.closeEntry();
            }
        }
        return jar;
    }
}
