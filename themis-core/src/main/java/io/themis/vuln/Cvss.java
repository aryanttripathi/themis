package io.themis.vuln;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** CVSS v3.0/v3.1 base score from a vector string (FIRST specification, section 7). */
final class Cvss {

    private Cvss() {
    }

    static Optional<Double> baseScore(String vector) {
        if (vector == null || !(vector.startsWith("CVSS:3.0/") || vector.startsWith("CVSS:3.1/"))) return Optional.empty();
        Map<String, String> m = new HashMap<>();
        for (String part : vector.substring(9).split("/")) {
            String[] kv = part.split(":");
            if (kv.length != 2) return Optional.empty();
            m.put(kv[0], kv[1]);
        }
        try {
            boolean changed = "C".equals(m.get("S"));
            double av = pick(m.get("AV"), "N", .85, "A", .62, "L", .55, "P", .2);
            double ac = pick(m.get("AC"), "L", .77, "H", .44);
            double pr = changed ? pick(m.get("PR"), "N", .85, "L", .68, "H", .5) : pick(m.get("PR"), "N", .85, "L", .62, "H", .27);
            double ui = pick(m.get("UI"), "N", .85, "R", .62);
            double c = pick(m.get("C"), "H", .56, "L", .22, "N", 0);
            double i = pick(m.get("I"), "H", .56, "L", .22, "N", 0);
            double a = pick(m.get("A"), "H", .56, "L", .22, "N", 0);
            if (!changed && !"U".equals(m.get("S"))) return Optional.empty();

            double iss = 1 - (1 - c) * (1 - i) * (1 - a);
            double impact = changed ? 7.52 * (iss - 0.029) - 3.25 * Math.pow(iss - 0.02, 15) : 6.42 * iss;
            double exploitability = 8.22 * av * ac * pr * ui;
            if (impact <= 0) return Optional.of(0.0);
            double raw = changed ? Math.min(1.08 * (impact + exploitability), 10) : Math.min(impact + exploitability, 10);
            return Optional.of(roundUp(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static double pick(String value, Object... pairs) {
        for (int k = 0; k < pairs.length; k += 2) {
            if (pairs[k].equals(value)) return ((Number) pairs[k + 1]).doubleValue();
        }
        throw new IllegalArgumentException("bad CVSS metric value " + value);
    }

    /** CVSS 3.1 Roundup: smallest number with one decimal >= input, robust to floating-point noise. */
    private static double roundUp(double x) {
        long n = Math.round(x * 100000);
        return n % 10000 == 0 ? n / 100000.0 : (Math.floor(n / 10000.0) + 1) / 10.0;
    }
}
