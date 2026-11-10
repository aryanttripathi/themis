package io.themis.model;

import java.util.List;
import java.util.Optional;

/**
 * Detected license of one dependency.
 *
 * @param spdx     SPDX expression ({@code A OR B} for dual licensing); empty when not recognised
 * @param source   where it was found: {@code config}, {@code pom}, {@code parent-pom}, {@code deps.dev},
 *                 {@code jar:<entry>} or {@code none}
 * @param declared raw declarations seen on the way (POM names/URLs, deps.dev strings), as written
 * @param notes    caveats: assumed variants of family names, unreachable sources, unreadable POMs
 */
public record LicenseInfo(Coordinate dependency, Optional<String> spdx, LicenseCategory category, String source,
                          List<String> declared, List<String> notes) {

    public LicenseInfo {
        declared = List.copyOf(declared);
        notes = List.copyOf(notes);
    }
}
