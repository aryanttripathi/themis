package io.themis.license;

import io.themis.model.LicenseCategory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LicenseClassifierTest {

    private final LicenseClassifier classifier = new LicenseClassifier();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "Apache-2.0                                    | PERMISSIVE",
            "MIT                                           | PERMISSIVE",
            "BSD-3-Clause                                  | PERMISSIVE",
            "LGPL-2.1-or-later                             | WEAK_COPYLEFT",
            "EPL-2.0                                       | WEAK_COPYLEFT",
            "GPL-3.0-only                                  | STRONG_COPYLEFT",
            "GPL-2.0+                                      | STRONG_COPYLEFT",
            "AGPL-3.0-only                                 | NETWORK_COPYLEFT",
            "SSPL-1.0                                      | NETWORK_COPYLEFT",
            "GPL-2.0-only WITH Classpath-exception-2.0     | WEAK_COPYLEFT",
            "EPL-2.0 OR GPL-2.0-with-classpath-exception   | WEAK_COPYLEFT",
            "Apache-2.0 OR AGPL-3.0-only                   | PERMISSIVE",
            "MIT AND GPL-3.0-only                          | STRONG_COPYLEFT",
            "(MIT OR Apache-2.0)                           | PERMISSIVE",
            "LicenseRef-Acme-Commercial                    | PROPRIETARY",
            "Some-Custom-License                           | UNKNOWN",
            "LicenseRef-Public-Domain                      | PERMISSIVE",
            "Unlicense                                     | PERMISSIVE",
            "CC0-1.0                                       | PERMISSIVE",
            "BSD-2-Clause                                  | PERMISSIVE",
            "JSON                                          | PROPRIETARY",
            "GPL-2.0-only WITH Universal-FOSS-exception-1.0 | STRONG_COPYLEFT",
            "CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0 | WEAK_COPYLEFT",
            "EPL-1.0 OR LGPL-2.1-only                      | WEAK_COPYLEFT",
    })
    void classifies(String spdx, LicenseCategory expected) {
        assertThat(classifier.classify(spdx)).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void blankIsUnknown() {
        assertThat(classifier.classify("  ")).isEqualTo(LicenseCategory.UNKNOWN);
        assertThat(classifier.classify(null)).isEqualTo(LicenseCategory.UNKNOWN);
    }
}
