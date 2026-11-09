package io.themis.model;

import java.util.List;

/**
 * Detected license of one dependency.
 *
 * @param source where it was found: config, pom, parent-pom, deps.dev, jar, none
 */
public record LicenseInfo(Coordinate dependency, List<String> spdxIds, LicenseCategory category, String source) {

    public LicenseInfo {
        spdxIds = List.copyOf(spdxIds);
    }
}
