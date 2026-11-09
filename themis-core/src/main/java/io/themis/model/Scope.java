package io.themis.model;

import java.util.List;
import java.util.Locale;

/** Maven dependency scope. */
public enum Scope {
    COMPILE, RUNTIME, PROVIDED, TEST, SYSTEM, IMPORT;

    /**
     * Exposure order used when one artifact is reached with different scopes (e.g. by several
     * modules): shipped scopes first ({@code compile} > {@code runtime}), then those supplied by the
     * environment ({@code provided} > {@code system}: a container-provided library is a real
     * runtime component, a system jar is a local file), then {@code test}.
     */
    private static final List<Scope> WIDEST_FIRST = List.of(COMPILE, RUNTIME, PROVIDED, SYSTEM, TEST, IMPORT);

    /** Maps a Maven scope name (locale-independent); empty or null means {@code compile}. */
    public static Scope fromMaven(String mavenScope) {
        return mavenScope == null || mavenScope.isEmpty() ? COMPILE : valueOf(mavenScope.toUpperCase(Locale.ROOT));
    }

    /** The name as written in a POM, e.g. {@code compile} (locale-independent). */
    public String mavenName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The more exposed of two scopes, see {@link #WIDEST_FIRST}. */
    public static Scope widest(Scope a, Scope b) {
        return WIDEST_FIRST.indexOf(a) <= WIDEST_FIRST.indexOf(b) ? a : b;
    }
}
