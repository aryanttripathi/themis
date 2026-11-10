package io.themis.license;

import io.themis.model.LicenseCategory;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Maps SPDX license identifiers and simple SPDX expressions to an obligation category.
 * <ul>
 *   <li>{@code A OR B}: the user may choose, so the least restrictive option wins.</li>
 *   <li>{@code A AND B}: both apply, so the most restrictive wins.</li>
 *   <li>{@code GPL-2.0-only WITH Classpath-exception-2.0}: linking is allowed, treated as weak copyleft.</li>
 * </ul>
 */
public final class LicenseClassifier {

    private static final Pattern OR = Pattern.compile("\\s+OR\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern AND = Pattern.compile("\\s+AND\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WITH = Pattern.compile("\\s+WITH\\s+", Pattern.CASE_INSENSITIVE);

    private static final Map<LicenseCategory, Set<String>> FAMILIES = Map.of(
            LicenseCategory.PERMISSIVE, Set.of(
                    "apache-2.0", "apache-1.1", "mit", "mit-0", "bsd-2-clause", "bsd-3-clause", "isc", "0bsd",
                    "unlicense", "cc0-1.0", "zlib", "bsl-1.0", "upl-1.0", "edl-1.0", "w3c", "postgresql",
                    "apache-1.0", "bsd-1-clause", "bsd-4-clause", "bsd-3-clause-clear", "cc-by-3.0", "cc-by-4.0",
                    "wtfpl", "x11", "icu", "unicode-dfs-2016", "unicode-3.0", "psf-2.0", "python-2.0", "blueoak-1.0.0",
                    "ms-pl", "ncsa", "libpng", "ijg", "afl-2.1", "afl-3.0", "openssl", "artistic-2.0"),
            LicenseCategory.WEAK_COPYLEFT, Set.of(
                    "lgpl-2.0", "lgpl-2.1", "lgpl-3.0", "mpl-1.1", "mpl-2.0", "epl-1.0", "epl-2.0",
                    "cddl-1.0", "cddl-1.1", "cpl-1.0", "mpl-1.0", "mpl-2.0-no-copyleft-exception", "eupl-1.1",
                    "eupl-1.2", "zpl-2.1", "apsl-2.0", "ofl-1.1", "cc-by-sa-4.0", "ruby"),
            LicenseCategory.STRONG_COPYLEFT, Set.of("gpl-1.0", "gpl-2.0", "gpl-3.0"),
            LicenseCategory.NETWORK_COPYLEFT, Set.of("agpl-3.0", "sspl-1.0"));

    /** Restrictiveness order used to combine expressions; UNKNOWN and PROPRIETARY need human review. */
    private static final Comparator<LicenseCategory> RESTRICTIVENESS = Comparator.comparingInt(c -> switch (c) {
        case PERMISSIVE -> 0;
        case WEAK_COPYLEFT -> 1;
        case STRONG_COPYLEFT -> 2;
        case NETWORK_COPYLEFT -> 3;
        case PROPRIETARY -> 4;
        case UNKNOWN -> 5;
    });

    public LicenseCategory classify(String spdxExpression) {
        if (spdxExpression == null || spdxExpression.isBlank()) return LicenseCategory.UNKNOWN;
        String expr = stripParens(spdxExpression.trim());

        if (OR.matcher(expr).find()) {
            return Stream.of(OR.split(expr)).map(this::classify).min(RESTRICTIVENESS).orElse(LicenseCategory.UNKNOWN);
        }
        if (AND.matcher(expr).find()) {
            return Stream.of(AND.split(expr)).map(this::classify).max(RESTRICTIVENESS).orElse(LicenseCategory.UNKNOWN);
        }
        String[] withParts = WITH.split(expr, 2);
        if (withParts.length == 2) {
            LicenseCategory base = classifySingle(withParts[0]);
            boolean classpathException = withParts[1].toLowerCase(Locale.ROOT).startsWith("classpath-exception");
            return base == LicenseCategory.STRONG_COPYLEFT && classpathException ? LicenseCategory.WEAK_COPYLEFT : base;
        }
        return classifySingle(expr);
    }

    private LicenseCategory classifySingle(String id) {
        String norm = id.trim().toLowerCase(Locale.ROOT);
        if (norm.equals("gpl-2.0-with-classpath-exception")) return LicenseCategory.WEAK_COPYLEFT;
        if (norm.equals("licenseref-public-domain")) return LicenseCategory.PERMISSIVE;
        // JSON License: MIT plus "shall be used for Good, not Evil", a field-of-use restriction that
        // makes it non-free (OSI/FSF) and needs legal review, like custom commercial terms.
        if (norm.equals("json")) return LicenseCategory.PROPRIETARY;
        if (norm.startsWith("licenseref-") || norm.equals("proprietary")) return LicenseCategory.PROPRIETARY;

        // Drop SPDX suffixes: -only, -or-later, and the deprecated "+" form.
        String family = norm.replaceAll("(-only|-or-later|\\+)$", "");
        for (Map.Entry<LicenseCategory, Set<String>> e : FAMILIES.entrySet()) {
            if (e.getValue().contains(family)) return e.getKey();
        }
        return LicenseCategory.UNKNOWN;
    }

    private static String stripParens(String s) {
        while (s.startsWith("(") && s.endsWith(")")) s = s.substring(1, s.length() - 1).trim();
        return s;
    }
}
