package io.themis.model;

/** Finding severity, ordered from least to most severe. */
public enum Severity {
    INFO, LOW, MEDIUM, HIGH, CRITICAL;

    public boolean atLeast(Severity other) {
        return this.compareTo(other) >= 0;
    }

    /** Maps a CVSS base score (0.0-10.0) to a severity. */
    public static Severity fromCvss(double score) {
        if (score >= 9.0) return CRITICAL;
        if (score >= 7.0) return HIGH;
        if (score >= 4.0) return MEDIUM;
        if (score > 0.0) return LOW;
        return INFO;
    }
}
