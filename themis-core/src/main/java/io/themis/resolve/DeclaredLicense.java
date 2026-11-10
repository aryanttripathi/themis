package io.themis.resolve;

/**
 * One {@code <license>} entry of a POM.
 *
 * @param name free-text name as written (may be null)
 * @param url  license URL as written (may be null)
 */
public record DeclaredLicense(String name, String url) {

    /** {@code name (url)}, {@code name} or {@code url}: how the declaration is shown in evidence. */
    public String display() {
        boolean hasName = name != null && !name.isBlank();
        boolean hasUrl = url != null && !url.isBlank();
        if (hasName && hasUrl) return name.trim() + " (" + url.trim() + ")";
        return hasName ? name.trim() : (hasUrl ? url.trim() : "");
    }
}
