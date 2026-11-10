package io.themis.license;

import io.themis.testing.Locales;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SpdxNormalizerTest {

    private final SpdxNormalizer normalizer = SpdxNormalizer.bundled();

    /** Names as found in real POMs (see docs/LLD.md section 5 for where they were collected). */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            The GNU General Public License, v2 with FOSS exception | GPL-2.0-only WITH Universal-FOSS-exception-1.0
            GNU General Public License, v2 with FOSS exception | GPL-2.0-only WITH Universal-FOSS-exception-1.0
            The Apache Software License, Version 2.0                                  | Apache-2.0
            Apache License, Version 2.0                                               | Apache-2.0
            The Apache License, Version 2.0                                           | Apache-2.0
            Apache License Version 2.0                                                | Apache-2.0
            Apache License, version 2.0                                               | Apache-2.0
            Apache License, 2.0                                                       | Apache-2.0
            Apache 2.0                                                                | Apache-2.0
            Apache 2                                                                  | Apache-2.0
            ASL 2.0                                                                   | Apache-2.0
            Apache Software License - Version 1.1                                     | Apache-1.1
            MIT License                                                               | MIT
            The MIT License                                                           | MIT
            The MIT License (MIT)                                                     | MIT
            Bouncy Castle Licence                                                     | MIT
            New BSD License                                                           | BSD-3-Clause
            Revised BSD License                                                       | BSD-3-Clause
            BSD 3-Clause License                                                      | BSD-3-Clause
            The BSD 3-Clause License                                                  | BSD-3-Clause
            Simplified BSD License                                                    | BSD-2-Clause
            BSD 2-Clause License                                                      | BSD-2-Clause
            Go License                                                                | BSD-3-Clause
            ISC License                                                               | ISC
            Eclipse Public License - v 1.0                                            | EPL-1.0
            Eclipse Public License v2.0                                               | EPL-2.0
            Eclipse Public License v. 2.0                                             | EPL-2.0
            Eclipse Public License - v 2.0                                            | EPL-2.0
            EPL 2.0                                                                   | EPL-2.0
            EPL 1.0                                                                   | EPL-1.0
            Eclipse Distribution License - v 1.0                                      | BSD-3-Clause
            Eclipse Distribution License v. 1.0                                       | BSD-3-Clause
            EDL 1.0                                                                   | BSD-3-Clause
            GNU Lesser General Public License version 3                               | LGPL-3.0-only
            GNU Lesser General Public License Version 2.1                             | LGPL-2.1-only
            GNU Library General Public License v2.1 or later                          | LGPL-2.1-or-later
            GNU Lesser General Public License, Version 2.1 or later                   | LGPL-2.1-or-later
            LGPL 2.1                                                                  | LGPL-2.1-only
            GNU General Public License Version 2                                      | GPL-2.0-only
            GNU General Public License, version 3                                     | GPL-3.0-only
            GPLv3                                                                     | GPL-3.0-only
            GPL2 w/ CPE                                                               | GPL-2.0-only WITH Classpath-exception-2.0
            GNU General Public License, version 2 with the GNU Classpath Exception    | GPL-2.0-only WITH Classpath-exception-2.0
            The GNU General Public License (GPL), Version 2, With Classpath Exception | GPL-2.0-only WITH Classpath-exception-2.0
            The GNU General Public License, v2 with Universal FOSS Exception, v1.0    | GPL-2.0-only WITH Universal-FOSS-exception-1.0
            CDDL + GPLv2 with classpath exception                                     | CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0
            CDDL/GPLv2+CE                                                             | CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0
            CDDL+GPL License                                                          | CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0
            Common Development and Distribution License (CDDL) v1.0                   | CDDL-1.0
            CDDL 1.1                                                                  | CDDL-1.1
            Mozilla Public License, Version 2.0                                       | MPL-2.0
            MPL 2.0                                                                   | MPL-2.0
            GNU Affero General Public License v3                                      | AGPL-3.0-only
            GNU Affero General Public License, Version 3 or later                     | AGPL-3.0-or-later
            Server Side Public License                                                | SSPL-1.0
            The Unlicense                                                             | Unlicense
            CC0                                                                       | CC0-1.0
            Creative Commons Zero v1.0 Universal                                      | CC0-1.0
            Public Domain                                                             | LicenseRef-Public-Domain
            The JSON License                                                          | JSON
            """)
    void normalizesCommonNames(String name, String spdx) {
        assertThat(normalizer.normalize(name, null)).hasValueSatisfying(n -> assertThat(n.spdx()).isEqualTo(spdx));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            http://www.apache.org/licenses/LICENSE-2.0.txt                 | Apache-2.0
            https://www.apache.org/licenses/LICENSE-2.0                    | Apache-2.0
            https://apache.org/licenses/LICENSE-2.0.html                   | Apache-2.0
            http://opensource.org/licenses/MIT                             | MIT
            https://opensource.org/license/mit/                            | MIT
            http://www.opensource.org/licenses/mit-license.php             | MIT
            https://www.eclipse.org/legal/epl-v10.html                     | EPL-1.0
            https://www.eclipse.org/legal/epl-2.0/                         | EPL-2.0
            http://www.eclipse.org/org/documents/edl-v10.php               | BSD-3-Clause
            https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html        | LGPL-2.1-only
            http://www.gnu.org/licenses/agpl-3.0.html                      | AGPL-3.0-only
            https://www.mozilla.org/MPL/2.0/                               | MPL-2.0
            https://spdx.org/licenses/BSD-2-Clause.html                    | BSD-2-Clause
            https://www.bouncycastle.org/licence.html                      | MIT
            """)
    void normalizesUrlsWhenTheNameIsUnknown(String url, String spdx) {
        assertThat(normalizer.normalize("Some Unrecognised Name", url))
                .hasValueSatisfying(n -> assertThat(n.spdx()).isEqualTo(spdx));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Apache-2.0                                         | Apache-2.0
            apache-2.0                                         | Apache-2.0
            MIT                                                | MIT
            EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0 | EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0
            (MIT OR Apache-2.0)                                | (MIT OR Apache-2.0)
            LGPL-2.1-or-later                                  | LGPL-2.1-or-later
            GPL-2.0                                            | GPL-2.0-only
            GPL-2.0+                                           | GPL-2.0-or-later
            GPL-2.0-with-classpath-exception                   | GPL-2.0-only WITH Classpath-exception-2.0
            """)
    void validSpdxIdsAndExpressionsPassThroughCanonicalised(String input, String spdx) {
        assertThat(normalizer.normalize(input, null)).hasValueSatisfying(n -> {
            assertThat(n.spdx()).isEqualTo(spdx);
            assertThat(n.matchedBy()).isEqualTo("spdx");
        });
    }

    @Test
    void nameIsPreferredOverUrlAndRecordsHowItMatched() {
        var n = normalizer.normalize("MIT License", "https://www.apache.org/licenses/LICENSE-2.0").orElseThrow();
        assertThat(n.spdx()).isEqualTo("MIT");
        assertThat(n.matchedBy()).isEqualTo("name");
        assertThat(normalizer.normalize(null, "https://www.apache.org/licenses/LICENSE-2.0").orElseThrow().matchedBy())
                .isEqualTo("url");
    }

    @Test
    void unversionedFamilyNamesAreFlaggedAmbiguous() {
        // Decision (docs/LLD.md 5): bare "BSD" -> BSD-3-Clause, bare "LGPL" -> LGPL-2.1-only, flagged ambiguous.
        var bsd = normalizer.normalize("The BSD License", null).orElseThrow();
        assertThat(bsd.spdx()).isEqualTo("BSD-3-Clause");
        assertThat(bsd.ambiguous()).isTrue();
        var lgpl = normalizer.normalize("GNU Lesser General Public License", null).orElseThrow();
        assertThat(lgpl.spdx()).isEqualTo("LGPL-2.1-only");
        assertThat(lgpl.ambiguous()).isTrue();
        assertThat(normalizer.normalize("New BSD License", null).orElseThrow().ambiguous()).isFalse();
    }

    @Test
    void unknownStringsStayUnknown() {
        assertThat(normalizer.normalize("Acme Corp Internal License Agreement", "https://acme.example/eula")).isEmpty();
        assertThat(normalizer.normalize("  ", "")).isEmpty();
        assertThat(normalizer.normalize(null, null)).isEmpty();
        // looks like an expression but contains an unknown id: not passed through
        assertThat(normalizer.normalize("Foo-1.0 OR Bar-2.0", null)).isEmpty();
    }

    @Test
    void worksUnderTurkishLocale() {
        assertThat(Locales.with(Locales.TURKISH, () -> normalizer.normalize("MIT License", null)))
                .hasValueSatisfying(n -> assertThat(n.spdx()).isEqualTo("MIT"));
        assertThat(Locales.with(Locales.TURKISH, () -> normalizer.normalize("lgpl-2.1-or-later", null)))
                .hasValueSatisfying(n -> assertThat(n.spdx()).isEqualTo("LGPL-2.1-or-later"));
    }
}
