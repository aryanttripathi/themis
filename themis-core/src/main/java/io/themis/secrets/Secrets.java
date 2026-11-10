package io.themis.secrets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Helpers shared by the secret rules: placeholder detection, entropy, masking. */
final class Secrets {

    private static final Set<String> WEAK = Set.of("changeme", "change_me", "change-me", "password", "passwd", "secret",
            "example", "sample", "dummy", "test", "testing", "null", "none", "true", "false", "todo", "fixme", "xxx",
            "yyy", "redacted", "masked", "notset", "not-set", "default", "admin", "root", "guest", "user", "username");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^(\\$\\{.*}|\\$[A-Za-z_][A-Za-z0-9_]*|\\{\\{.*}}|<.*>|%\\(.*\\)s?|%[A-Za-z_]+%|ENC\\(.*\\)|\\*+|x+|\\.+|#\\{.*}|@.*@)$");

    private Secrets() {
    }

    /** A value that is clearly not a real secret (reference, template, example word, too short or too regular). */
    static boolean looksReal(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        if (v.length() < 8 || v.contains(" ")) return false;
        if (PLACEHOLDER.matcher(v).matches() || v.contains("${") || v.contains("{{")) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        if (WEAK.contains(lower) || lower.startsWith("your") || lower.startsWith("http") || lower.startsWith("classpath:")
                || lower.startsWith("file:") || lower.contains("example")) return false;
        return entropy(v) >= 3.0;
    }

    /** Shannon entropy in bits per character. */
    static double entropy(String s) {
        Map<Character, Integer> counts = new HashMap<>();
        for (char c : s.toCharArray()) counts.merge(c, 1, Integer::sum);
        double h = 0;
        for (int n : counts.values()) {
            double p = (double) n / s.length();
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    /** {@code AKIA****LE}: enough to recognise, never enough to use. */
    static String mask(String secret) {
        String s = secret.trim();
        if (s.length() <= 8) return "****";
        return s.substring(0, 4) + "****" + s.substring(s.length() - 2);
    }

    /** Short stable fingerprint of a line with the secret replaced by its mask (never hashes the secret itself). */
    static String fingerprint(String line, String secret) {
        String normalized = line.replace(secret, mask(secret)).trim();
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
