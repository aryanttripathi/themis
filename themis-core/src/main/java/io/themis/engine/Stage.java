package io.themis.engine;

/** Execution stage. Stages run in declaration order; analyzers within a stage may run in parallel. */
public enum Stage {
    /** Works on the dependency graph (licenses, vulnerabilities, Java version). */
    DEPENDENCY,
    /** Works on source and config files (secrets, PII). Independent of DEPENDENCY. */
    SOURCE,
    /** Combines results of earlier stages (upgrade advice). */
    ADVISOR
}
