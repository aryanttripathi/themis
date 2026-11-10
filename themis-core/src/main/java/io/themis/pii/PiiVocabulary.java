package io.themis.pii;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Recognises identifiers that hold personal data, by whole tokens of camelCase / snake_case names:
 * {@code customerPhoneNo} -> phone, {@code DATE_OF_BIRTH} -> date of birth, but not {@code company}
 * ("pan" inside a word) or {@code emailTemplateId} (ids, templates, counts... describe PII, they are not PII).
 */
public final class PiiVocabulary {

    private static final List<String[]> TERMS = List.of(
            t("date of birth", "date", "of", "birth"), t("date of birth", "birth", "date"), t("date of birth", "birthdate"),
            t("date of birth", "dob"), t("card number", "card", "number"), t("card number", "card", "no"),
            t("account number", "account", "number"), t("account number", "account", "no"), t("account number", "acc", "no"),
            t("first name", "first", "name"), t("last name", "last", "name"), t("full name", "full", "name"),
            t("pincode", "pin", "code"), t("pincode", "zip", "code"), t("pincode", "postal", "code"),
            t("email", "email"), t("email", "mail", "id"), t("phone", "phone"), t("phone", "telephone"), t("phone", "contact", "number"), t("phone", "contact", "no"), t("mobile", "mobile"), t("msisdn", "msisdn"),
            t("aadhaar", "aadhaar"), t("aadhaar", "aadhar"), t("pan", "pan"), t("address", "address"), t("pincode", "pincode"),
            t("pincode", "zipcode"), t("passport", "passport"), t("ssn", "ssn"), t("upi", "upi"), t("upi", "vpa"),
            t("ifsc", "ifsc"), t("cvv", "cvv"), t("gstin", "gstin"), t("voter id", "voter", "id"), t("driving licence", "driving", "licence"),
            t("driving licence", "driving", "license"), t("ip address", "ip", "address"));

    /** Tokens that make a PII-looking name describe metadata about PII rather than hold it. */
    private static final Set<String> METADATA = Set.of("id", "ids", "template", "templates", "count", "counts", "type",
            "types", "enabled", "regex", "pattern", "validator", "format", "length", "key", "config", "status", "required",
            "field", "fields", "label", "column", "prefix", "suffix", "verified", "sent", "exists", "valid", "masked",
            "hash", "hashed", "encrypted", "flag", "is", "has", "token", "service", "repository", "client", "event", "events", "request", "response",
            "util", "utils", "helper", "mapper", "dto", "list", "map", "provider", "sender", "domain", "change", "update");

    private final Set<String> extra;
    private final Set<String> ignore;

    private PiiVocabulary(Set<String> extra, Set<String> ignore) {
        this.extra = lower(extra);
        this.ignore = lower(ignore);
    }

    public static PiiVocabulary defaults() {
        return of(Set.of(), Set.of());
    }

    public static PiiVocabulary of(Set<String> extraFields, Set<String> ignoredFields) {
        return new PiiVocabulary(extraFields, ignoredFields);
    }

    /** What kind of PII this identifier holds, if any. */
    public Optional<String> match(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String lower = identifier.toLowerCase(Locale.ROOT);
        if (ignore.contains(lower)) return Optional.empty();
        if (extra.contains(lower)) return Optional.of(identifier);
        List<String> tokens = tokens(identifier);
        if (tokens.stream().anyMatch(METADATA::contains)) return Optional.empty();
        for (String[] term : TERMS) {
            int n = term.length - 1;
            for (int i = 0; i + n <= tokens.size(); i++) {
                boolean all = true;
                for (int k = 0; k < n && all; k++) all = tokens.get(i + k).equals(term[k + 1]);
                if (all) return Optional.of(term[0]);
            }
        }
        return Optional.empty();
    }

    static List<String> tokens(String identifier) {
        String spaced = identifier.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ").replaceAll("([A-Za-z])(\\d)", "$1 $2").trim().toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String t : spaced.split(" +")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private static String[] t(String label, String... tokens) {
        String[] out = new String[tokens.length + 1];
        out[0] = label;
        System.arraycopy(tokens, 0, out, 1, tokens.length);
        return out;
    }

    private static Set<String> lower(Set<String> s) {
        Set<String> out = new java.util.HashSet<>();
        s.forEach(x -> out.add(x.toLowerCase(Locale.ROOT)));
        return Set.copyOf(out);
    }
}
