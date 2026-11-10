package io.themis.resolve;

import io.themis.model.Coordinate;

/**
 * Build-tool metadata about resolved dependencies that analyzers may need, served by the scan's
 * {@link ProjectSession} (for Maven: read from the dependencies' effective POMs).
 */
@FunctionalInterface
public interface DependencyMetadata {

    /** No metadata (e.g. a resolver that does not read POMs). */
    DependencyMetadata NONE = dependency -> PomLicenses.NONE;

    /** License declarations of the dependency's POM (for a classified artifact: of its main artifact). */
    PomLicenses pomLicenses(Coordinate dependency);
}
