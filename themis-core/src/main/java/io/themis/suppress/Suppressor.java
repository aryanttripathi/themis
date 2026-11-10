package io.themis.suppress;

import io.themis.config.LicensePolicy;
import io.themis.model.DependencyGraph;
import io.themis.model.Finding;
import io.themis.model.ScanReport;
import io.themis.model.SuppressedFinding;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies {@code suppress:} entries and the baseline to a finished report. Suppressed findings move to
 * {@link ScanReport#suppressed()} (still visible in reports, never counted for the exit code).
 * Expired entries and entries matching nothing become warnings, so suppressions do not rot silently.
 */
public final class Suppressor {

    private Suppressor() {
    }

    public static ScanReport apply(ScanReport report, List<Suppression> rules, Baseline baseline, LocalDate today) {
        List<String> warnings = new ArrayList<>(report.graph().warnings());
        List<Suppression> active = new ArrayList<>();
        for (Suppression s : rules) {
            if (s.until().isPresent() && s.until().get().isBefore(today)) {
                warnings.add("suppression '" + s.id() + "' expired on " + s.until().get() + " (" + s.reason() + "): its findings are reported again");
            } else {
                active.add(s);
            }
        }
        Set<Suppression> used = new LinkedHashSet<>();
        List<Finding> kept = new ArrayList<>();
        List<SuppressedFinding> suppressed = new ArrayList<>(report.suppressed());
        for (Finding f : report.findings()) {
            Suppression match = active.stream().filter(s -> matches(s.id(), f.id())).findFirst().orElse(null);
            if (match != null) {
                used.add(match);
                suppressed.add(new SuppressedFinding(f, "suppressed: " + match.reason()));
            } else if (baseline.ids().contains(f.id())) {
                suppressed.add(new SuppressedFinding(f, "baseline"));
            } else {
                kept.add(f);
            }
        }
        for (Suppression s : active) {
            if (!used.contains(s)) warnings.add("suppression '" + s.id() + "' matched nothing: remove it from .themis.yml if the issue is gone");
        }
        DependencyGraph graph = new DependencyGraph(report.graph().root(), report.graph().dependencies(), warnings);
        return new ScanReport(report.toolVersion(), report.startedAt(), report.duration(), report.project(), graph,
                kept, report.errors(), report.data(), suppressed);
    }

    static boolean matches(String pattern, String id) {
        return pattern.equals(id) || (pattern.contains("*") && LicensePolicy.matches(pattern, id));
    }
}
