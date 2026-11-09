package io.themis.resolve;

import java.util.List;

/**
 * The project's dependency graph (or its effective POM) could not be resolved.
 * The message names the project and every artifact that could not be resolved, so the CLI can
 * print it as is ({@code error: ...}, exit code 2).
 */
public final class ResolutionException extends RuntimeException {

    private final List<String> unresolved;

    /**
     * @param unresolved artifacts that could not be resolved, as {@code g:a:extension[:classifier]:version};
     *                   empty if the failure is not about a specific artifact
     */
    public ResolutionException(String message, List<String> unresolved, Throwable cause) {
        super(message, cause);
        this.unresolved = List.copyOf(unresolved);
    }

    public List<String> unresolved() {
        return unresolved;
    }
}
