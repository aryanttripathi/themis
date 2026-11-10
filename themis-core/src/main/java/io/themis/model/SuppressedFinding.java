package io.themis.model;

/** A finding hidden by a {@code suppress:} entry or the baseline, with why ({@code baseline} or {@code suppressed: <reason>}). */
public record SuppressedFinding(Finding finding, String reason) {
}
