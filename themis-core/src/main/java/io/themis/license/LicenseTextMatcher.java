package io.themis.license;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Last-resort license detection from license files inside a jar, by fingerprinting well-known
 * texts with a few distinctive phrases (no full-text comparison).
 * <p>
 * Files considered, in priority order: {@code META-INF/LICENSE*}, {@code META-INF/<dir>/LICENSE*}
 * (one level, e.g. {@code META-INF/maven/...}), root {@code LICENSE*} / {@code COPYING*}. The first
 * file whose text matches wins. Families are checked most specific / most restrictive first
 * (AGPL before LGPL before GPL), so a text that mentions several licenses is classified conservatively.
 */
public final class LicenseTextMatcher {

    /** @param entry the jar entry the text came from, e.g. {@code META-INF/LICENSE} */
    public record Match(String spdx, String entry) {
    }

    private record Fingerprint(String spdx, Predicate<String> test) {
    }

    private static final int MAX_BYTES = 128 * 1024;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<Fingerprint> FINGERPRINTS = List.of(
            fp("AGPL-3.0-only", t -> t.contains("gnu affero general public license")),
            fp("SSPL-1.0", t -> t.contains("server side public license")),
            fp("LGPL-3.0-only", t -> t.contains("gnu lesser general public license") && t.contains("version 3")),
            fp("LGPL-2.1-only", t -> t.contains("gnu lesser general public license") && t.contains("version 2.1")),
            fp("LGPL-2.0-only", t -> t.contains("gnu library general public license") && t.contains("version 2")),
            fp("GPL-2.0-only WITH Classpath-exception-2.0", t -> t.contains("gnu general public license")
                    && t.contains("version 2") && t.contains("classpath exception")),
            fp("GPL-3.0-only", t -> t.contains("gnu general public license") && t.contains("version 3")),
            fp("GPL-2.0-only", t -> t.contains("gnu general public license") && t.contains("version 2")),
            fp("EPL-2.0", t -> t.contains("eclipse public license") && (t.contains("v 2.0") || t.contains("v. 2.0")
                    || t.contains("version 2.0"))),
            fp("EPL-1.0", t -> t.contains("eclipse public license") && (t.contains("v 1.0") || t.contains("v1.0")
                    || t.contains("version 1.0"))),
            fp("BSD-3-Clause", t -> t.contains("eclipse distribution license")),
            fp("MPL-2.0", t -> t.contains("mozilla public license") && (t.contains("version 2.0") || t.contains("v. 2.0"))),
            fp("CDDL-1.1", t -> t.contains("common development and distribution license") && t.contains("1.1")),
            fp("CDDL-1.0", t -> t.contains("common development and distribution license")),
            fp("Apache-2.0", t -> t.contains("apache license") && t.contains("version 2.0")),
            fp("MIT", t -> t.contains("permission is hereby granted, free of charge, to any person obtaining a copy")),
            fp("BSD-3-Clause", t -> t.contains("redistribution and use in source and binary forms")
                    && t.contains("neither the name")),
            fp("BSD-2-Clause", t -> t.contains("redistribution and use in source and binary forms")
                    && !t.contains("advertising materials")),
            fp("ISC", t -> t.contains("permission to use, copy, modify, and/or distribute this software for any purpose")),
            fp("Unlicense", t -> t.contains("this is free and unencumbered software released into the public domain")),
            fp("CC0-1.0", t -> t.contains("cc0 1.0 universal")));

    /** The best match, or empty if the jar has no recognisable license file (or cannot be read as a jar). */
    public Optional<Match> match(Path jar) {
        if (!Files.isRegularFile(jar)) return Optional.empty();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            List<ZipEntry> candidates = new ArrayList<>();
            zip.stream().filter(e -> !e.isDirectory() && priority(e.getName()) < Integer.MAX_VALUE).forEach(candidates::add);
            candidates.sort(Comparator.comparingInt((ZipEntry e) -> priority(e.getName())).thenComparing(ZipEntry::getName));
            for (ZipEntry entry : candidates) {
                String text = normalise(read(zip, entry));
                for (Fingerprint f : FINGERPRINTS) {
                    if (f.test().test(text)) return Optional.of(new Match(f.spdx(), entry.getName()));
                }
            }
            return Optional.empty();
        } catch (ZipException e) {
            return Optional.empty(); // not a jar (e.g. a misnamed file): nothing to read, not an error
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + jar, e);
        }
    }

    /** Lower is better; {@link Integer#MAX_VALUE} = not a license file. */
    static int priority(String name) {
        String n = name.toUpperCase(Locale.ROOT);
        String[] parts = n.split("/");
        String file = parts[parts.length - 1];
        boolean licenseFile = file.startsWith("LICENSE") || file.startsWith("LICENCE");
        if (parts.length == 2 && parts[0].equals("META-INF") && licenseFile) return 0;
        if (parts.length == 3 && parts[0].equals("META-INF") && licenseFile) return 1;
        if (parts.length == 1 && licenseFile) return 2;
        if (parts.length == 1 && file.startsWith("COPYING")) return 3;
        return Integer.MAX_VALUE;
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static String normalise(String text) {
        return WHITESPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private static Fingerprint fp(String spdx, Predicate<String> test) {
        return new Fingerprint(spdx, test);
    }
}
