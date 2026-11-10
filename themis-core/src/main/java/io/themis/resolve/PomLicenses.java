package io.themis.resolve;

import java.util.List;
import java.util.Optional;

/**
 * The {@code <licenses>} of a dependency's effective POM.
 *
 * @param licenses  entries in POM order (several entries mean the artifact is offered under any of them)
 * @param inherited true if the dependency's own POM declares none and they come from a parent POM
 * @param problem   why the POM could not be read, if it could not (licenses is then empty)
 */
public record PomLicenses(List<DeclaredLicense> licenses, boolean inherited, Optional<String> problem) {

    public static final PomLicenses NONE = new PomLicenses(List.of(), false, Optional.empty());

    public PomLicenses {
        licenses = List.copyOf(licenses);
    }

    public static PomLicenses unreadable(String problem) {
        return new PomLicenses(List.of(), false, Optional.of(problem));
    }
}
