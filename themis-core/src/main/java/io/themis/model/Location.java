package io.themis.model;

import java.nio.file.Path;

/** A position in a source or config file (1-based line and column). */
public record Location(Path file, int line, int column) {
}
