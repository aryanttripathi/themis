package io.themis.license;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps free-text license names and URLs (as found in POM {@code <licenses>}) to SPDX expressions,
 * using the bundled {@code aliases.tsv} and {@code spdx-ids.txt} resources.
 * <ol>
 *   <li>A valid SPDX id or expression (case-insensitive, deprecated ids upgraded, e.g.
 *       {@code GPL-2.0} to {@code GPL-2.0-only}; {@code LicenseRef-*} accepted) passes through, canonicalised.</li>
 *   <li>Name lookup: lowercase, split letters from digits ({@code GPLv3} = {@code GPL v3}),
 *       strip punctuation and noise words (the, license, licence, version, v, software), drop
 *       {@code .0} from version numbers ({@code 2.0} = {@code 2}), then exact alias lookup.</li>
 *   <li>URL lookup: scheme, {@code www.}, query, trailing slash and {@code .txt/.html/.htm/.php/.md}
 *       ignored; {@code spdx.org/licenses/<id>} is read as the id.</li>
 * </ol>
 * Anything else is not normalised (the caller keeps it as UNKNOWN with the raw text).
 */
public final class SpdxNormalizer {

    /**
     * @param spdx      canonical SPDX expression
     * @param ambiguous the source named only a license family (e.g. "BSD License"); the most common
     *                  variant was assumed (the category does not depend on the variant)
     * @param matchedBy {@code spdx}, {@code name} or {@code url}
     */
    public record Normalized(String spdx, boolean ambiguous, String matchedBy) {
    }

    private record Alias(String spdx, boolean ambiguous) {
    }

    private static final Set<String> NOISE = Set.of(
            "the", "license", "licence", "licenses", "licences", "licensed", "version", "v", "ver", "software");
    private static final Pattern LETTER_DIGIT = Pattern.compile("(?<=[a-z])(?=\\d)");
    private static final Pattern NON_KEY_CHARS = Pattern.compile("[^a-z0-9.+]+");
    private static final Pattern TRAILING_ZERO = Pattern.compile("^(\\d+)\\.0$");
    private static final Pattern TOKENS = Pattern.compile("\\(|\\)|[^\\s()]+");
    private static final Pattern OPERATOR = Pattern.compile("(?i)OR|AND|WITH");
    /** SPDX custom license references, e.g. {@code LicenseRef-Acme-Commercial}: accepted as written. */
    private static final Pattern LICENSE_REF = Pattern.compile("(?i)LicenseRef-[A-Za-z0-9.\\-]+");
    private static final Pattern URL_SUFFIX = Pattern.compile("\\.(txt|html|htm|php|md|json)$");
    private static final String SPDX_URL_PREFIX = "spdx.org/licenses/";

    private static volatile SpdxNormalizer bundled;

    /** Lower-cased id -> canonical form (possibly an upgraded expression for deprecated ids). */
    private final Map<String, String> spdxIds;
    private final Map<String, Alias> names;
    private final Map<String, Alias> urls;

    private SpdxNormalizer(Map<String, String> spdxIds, Map<String, Alias> names, Map<String, Alias> urls) {
        this.spdxIds = spdxIds;
        this.names = names;
        this.urls = urls;
    }

    /** The normalizer over the bundled tables (loaded once). */
    public static SpdxNormalizer bundled() {
        SpdxNormalizer n = bundled;
        if (n == null) {
            synchronized (SpdxNormalizer.class) {
                if (bundled == null) bundled = load();
                n = bundled;
            }
        }
        return n;
    }

    /** Name first, then URL; empty if neither is recognised. */
    public Optional<Normalized> normalize(String name, String url) {
        if (name != null && !name.isBlank()) {
            Optional<String> spdx = asSpdxExpression(name.trim());
            if (spdx.isPresent()) return Optional.of(new Normalized(spdx.get(), false, "spdx"));
            Alias alias = names.get(nameKey(name));
            if (alias != null) return Optional.of(new Normalized(alias.spdx(), alias.ambiguous(), "name"));
        }
        if (url != null && !url.isBlank()) {
            String key = urlKey(url);
            Alias alias = urls.get(key);
            if (alias != null) return Optional.of(new Normalized(alias.spdx(), alias.ambiguous(), "url"));
            if (key.startsWith(SPDX_URL_PREFIX)) {
                return asSpdxExpression(key.substring(SPDX_URL_PREFIX.length()))
                        .map(spdx -> new Normalized(spdx, false, "url"));
            }
        }
        return Optional.empty();
    }

    /** Canonical SPDX expression if every operand is a known id, else empty. */
    Optional<String> asSpdxExpression(String text) {
        Matcher m = TOKENS.matcher(text);
        List<String> out = new ArrayList<>();
        boolean sawOperand = false;
        while (m.find()) {
            String token = m.group();
            if (token.equals("(") || token.equals(")")) {
                out.add(token);
            } else if (OPERATOR.matcher(token).matches()) {
                out.add(token.toUpperCase(Locale.ROOT));
            } else {
                String canonical = spdxIds.get(token.toLowerCase(Locale.ROOT));
                if (canonical == null && LICENSE_REF.matcher(token).matches()) {
                    canonical = "LicenseRef-" + token.substring("LicenseRef-".length());
                }
                if (canonical == null) return Optional.empty();
                out.add(canonical);
                sawOperand = true;
            }
        }
        if (!sawOperand) return Optional.empty();
        return Optional.of(String.join(" ", out).replace("( ", "(").replace(" )", ")"));
    }

    static String nameKey(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace("&", " and ");
        s = LETTER_DIGIT.matcher(s).replaceAll(" ");
        String[] tokens = NON_KEY_CHARS.matcher(s).replaceAll(" ").trim().split(" +");
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String t = stripDots(tokens[i]);
            boolean beforeNumber = i + 1 < tokens.length && !tokens[i + 1].isEmpty() && Character.isDigit(tokens[i + 1].charAt(0));
            if (t.length() > 1 && t.endsWith("v") && beforeNumber) t = t.substring(0, t.length() - 1); // gplv 3 -> gpl 3
            Matcher zero = TRAILING_ZERO.matcher(t);
            if (zero.matches()) t = zero.group(1);
            if (!t.isEmpty() && !NOISE.contains(t)) kept.add(t);
        }
        return String.join(" ", kept);
    }

    static String urlKey(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^[a-z]+://", "");
        s = s.replaceFirst("^www\\.", "");
        int cut = indexOfAny(s, '?', '#');
        if (cut >= 0) s = s.substring(0, cut);
        s = s.replaceAll("/+$", "");
        s = URL_SUFFIX.matcher(s).replaceFirst("");
        return s.replaceAll("/+$", "");
    }

    private static String stripDots(String t) {
        int start = 0;
        int end = t.length();
        while (start < end && t.charAt(start) == '.') start++;
        while (end > start && t.charAt(end - 1) == '.') end--;
        return t.substring(start, end);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        return i < 0 ? j : (j < 0 ? i : Math.min(i, j));
    }

    // --- loading ---------------------------------------------------------------------------

    private static SpdxNormalizer load() {
        Map<String, String> ids = new HashMap<>();
        for (String line : lines("spdx-ids.txt")) {
            String[] p = line.split("=>");
            String id = p[0].trim();
            ids.put(id.toLowerCase(Locale.ROOT), p.length > 1 ? p[1].trim() : id);
        }
        Map<String, Alias> names = new HashMap<>();
        Map<String, Alias> urls = new HashMap<>();
        for (String line : lines("aliases.tsv")) {
            String[] p = line.split("\t");
            if (p.length < 3) throw new IllegalStateException("Malformed aliases.tsv line: " + line);
            Alias alias = new Alias(p[2].trim(), p.length > 3 && "ambiguous".equals(p[3].trim()));
            switch (p[0].trim()) {
                case "name" -> put(names, nameKey(p[1]), alias, line);
                case "url" -> put(urls, urlKey(p[1]), alias, line);
                default -> throw new IllegalStateException("Unknown alias kind in aliases.tsv: " + line);
            }
        }
        return new SpdxNormalizer(Map.copyOf(ids), Map.copyOf(names), Map.copyOf(urls));
    }

    /** Two raw spellings may normalise to the same key, but never to different licenses. */
    private static void put(Map<String, Alias> map, String key, Alias alias, String line) {
        Alias previous = map.putIfAbsent(key, alias);
        if (previous != null && !previous.equals(alias)) {
            throw new IllegalStateException("aliases.tsv: '" + key + "' maps to both " + previous.spdx()
                    + " and " + alias.spdx() + " (line: " + line + ")");
        }
    }

    private static List<String> lines(String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream in = SpdxNormalizer.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing bundled resource io/themis/license/" + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = reader.readLine()) != null; ) {
                if (!line.isBlank() && !line.startsWith("#")) out.add(line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        return out;
    }
}
