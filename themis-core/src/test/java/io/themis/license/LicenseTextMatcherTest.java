package io.themis.license;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseTextMatcherTest {

    @TempDir
    Path tmp;

    static final String APACHE = """
                                             Apache License
                                       Version 2.0, January 2004
                                    http://www.apache.org/licenses/
               TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION""";
    static final String MIT = """
            MIT License  Copyright (c) 2020 Someone
            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal""";
    static final String BSD3 = """
            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions are met:
            3. Neither the name of the copyright holder nor the names of its contributors""";
    static final String BSD2 = """
            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions are met:
            1. Redistributions of source code must retain the above copyright notice""";
    static final String LGPL21 = """
                              GNU LESSER GENERAL PUBLIC LICENSE
                                   Version 2.1, February 1999""";
    static final String GPL2CE = """
            GNU GENERAL PUBLIC LICENSE  Version 2, June 1991
            ... CLASSPATH EXCEPTION  Linking this library statically or dynamically with other modules""";
    static final String AGPL = """
                                GNU AFFERO GENERAL PUBLIC LICENSE
                                   Version 3, 19 November 2007""";
    static final String EPL2 = "Eclipse Public License - v 2.0\n THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            APACHE | META-INF/LICENSE         | Apache-2.0
            MIT    | META-INF/LICENSE.txt     | MIT
            BSD3   | LICENSE                  | BSD-3-Clause
            BSD2   | LICENSE.md               | BSD-2-Clause
            LGPL21 | META-INF/LICENSE         | LGPL-2.1-only
            GPL2CE | META-INF/LICENSE         | GPL-2.0-only WITH Classpath-exception-2.0
            AGPL   | META-INF/LICENSE         | AGPL-3.0-only
            EPL2   | META-INF/acme/LICENSE.md | EPL-2.0
            """)
    void recognisesWellKnownTexts(String text, String entry, String spdx) throws IOException {
        Path jar = jar(Map.of(entry, text(text)));

        assertThat(new LicenseTextMatcher().match(jar)).hasValueSatisfying(m -> {
            assertThat(m.spdx()).isEqualTo(spdx);
            assertThat(m.entry()).isEqualTo(entry);
        });
    }

    @Test
    void prefersMetaInfLicenseOverBundledThirdPartyLicenses() throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("META-INF/licenses/asm/LICENSE", BSD3);
        entries.put("META-INF/LICENSE", APACHE);
        Path jar = jar(entries);

        assertThat(new LicenseTextMatcher().match(jar)).hasValueSatisfying(m -> assertThat(m.spdx()).isEqualTo("Apache-2.0"));
    }

    @Test
    void unrecognisedTextOrNoLicenseFileIsEmpty() throws IOException {
        assertThat(new LicenseTextMatcher().match(jar(Map.of("META-INF/LICENSE", "All rights reserved. Acme Corp.")))).isEmpty();
        assertThat(new LicenseTextMatcher().match(jar(Map.of("com/acme/Foo.class", "x")))).isEmpty();
        assertThat(new LicenseTextMatcher().match(tmp.resolve("missing.jar"))).isEmpty();
    }

    private static String text(String constantName) {
        return switch (constantName) {
            case "APACHE" -> APACHE;
            case "MIT" -> MIT;
            case "BSD3" -> BSD3;
            case "BSD2" -> BSD2;
            case "LGPL21" -> LGPL21;
            case "GPL2CE" -> GPL2CE;
            case "AGPL" -> AGPL;
            case "EPL2" -> EPL2;
            default -> throw new IllegalArgumentException(constantName);
        };
    }

    private Path jar(Map<String, String> entries) throws IOException {
        Path jar = Files.createTempFile(tmp, "lib", ".jar");
        try (OutputStream os = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(os)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
