package io.themis.vuln;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CvssTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource({
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H, 9.8",
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:H, 10.0",
            "CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:N/A:N, 5.9",
            "CVSS:3.1/AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H, 7.8",
            "CVSS:3.0/AV:N/AC:L/PR:L/UI:N/S:C/C:L/I:L/A:N, 6.4",
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N, 6.1",
            "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N, 0.0",
    })
    void computesCvss3BaseScores(String vector, double score) {
        assertThat(Cvss.baseScore(vector)).hasValue(score);
    }

    @Test
    void rejectsGarbageAndV4Vectors() {
        assertThat(Cvss.baseScore("not a vector")).isEmpty();
        assertThat(Cvss.baseScore("CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N")).isEmpty();
    }
}
