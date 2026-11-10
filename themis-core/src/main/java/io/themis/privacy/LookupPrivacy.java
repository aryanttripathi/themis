package io.themis.privacy;

import io.themis.config.LicensePolicy;
import io.themis.model.Coordinate;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which dependency coordinates may be sent to public vulnerability/license APIs. Only
 * {@code group:artifact:version} of public packages ever leaves the machine; never internal
 * packages (they would reveal internal names), snapshots (almost always internal builds), paths,
 * project names or anything about the user.
 */
public final class LookupPrivacy {

    private static final Pattern TIMESTAMPED_SNAPSHOT = Pattern.compile(".*-\\d{8}\\.\\d{6}-\\d+$");

    private final Set<String> patterns;

    private LookupPrivacy(Set<String> patterns) {
        this.patterns = Set.copyOf(patterns);
    }

    public static LookupPrivacy of(PrivacyConfig config, Coordinate project) {
        Set<String> p = new LinkedHashSet<>(config.privateArtifacts());
        if (config.projectGroupPrivate()) {
            projectGroupPattern(project.groupId()).ifPresent(g -> {
                p.add(g + ":*");       // the organisation group itself
                p.add(g + ".*");       // and every subgroup; "com.acmex" stays public
            });
        }
        return new LookupPrivacy(p);
    }

    /** First two segments of the project's groupId ("com.acme.reviews" -> "com.acme"). */
    static Optional<String> projectGroupPattern(String groupId) {
        if (groupId == null || groupId.isBlank() || groupId.equals("unspecified")) return Optional.empty();
        String[] seg = groupId.split("\\.");
        return Optional.of(seg.length == 1 ? seg[0] : seg[0] + "." + seg[1]);
    }

    /** True if this coordinate may be sent to a public API. */
    public boolean isPublic(Coordinate c) {
        return reasonPrivate(c).isEmpty();
    }

    /** Why this coordinate is kept local, or empty if it may be sent. */
    public Optional<String> reasonPrivate(Coordinate c) {
        String v = c.version();
        if (v.endsWith("-SNAPSHOT") || TIMESTAMPED_SNAPSHOT.matcher(v).matches()) return Optional.of("snapshot version (kept private)");
        String ga = c.ga();
        String gav = ga + ":" + v;
        for (String p : patterns) {
            String exact = p.contains(":") ? p : p + ":*";
            if (p.equals(ga) || p.equals(gav) || LicensePolicy.matches(p, ga) || LicensePolicy.matches(exact, ga)) {
                return Optional.of("internal package (kept private)");
            }
        }
        return Optional.empty();
    }
}
