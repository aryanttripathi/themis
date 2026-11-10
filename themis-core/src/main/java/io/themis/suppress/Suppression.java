package io.themis.suppress;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code suppress:} entry of .themis.yml.
 *
 * @param id     finding id or a {@code *} pattern (e.g. {@code VULNERABILITY:GHSA-*:com.mysql:*})
 * @param reason why it is acceptable (mandatory: a suppression without a reason is a silent risk)
 * @param until  last day it applies; afterwards the finding comes back and a warning says so
 */
public record Suppression(String id, String reason, Optional<LocalDate> until) {

    public Suppression {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(until, "until");
    }
}
