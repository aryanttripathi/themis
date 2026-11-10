package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ConfigFile;
import io.themis.config.ConfigLoader;
import io.themis.model.ScanReport;
import io.themis.model.SuppressedFinding;
import io.themis.suppress.Baseline;
import io.themis.suppress.Suppression;
import io.themis.suppress.Suppressor;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** CLI glue: .themis.yml {@code suppress:} entries plus the baseline file, applied to a finished scan. */
final class Suppressions {

    private Suppressions() {
    }

    static List<Suppression> fromConfig(Path projectDir, Path configFile) {
        return ConfigLoader.load(projectDir, Optional.ofNullable(configFile), Themis.analyzerNames())
                .map(ConfigFile::suppressions).orElse(List.of());
    }

    static Path baselineFile(Path projectDir, Path explicit) {
        return explicit != null ? explicit : projectDir.resolve(Baseline.FILE_NAME);
    }

    static ScanReport apply(ScanReport report, Path projectDir, Path configFile, Path baseline, boolean noBaseline) {
        Baseline b = noBaseline ? Baseline.EMPTY : Baseline.readIfExists(baselineFile(projectDir, baseline));
        return Suppressor.apply(report, fromConfig(projectDir, configFile), b, LocalDate.now());
    }

    /** "3 (2 baseline, 1 suppress)" */
    static String summary(List<SuppressedFinding> suppressed) {
        long baseline = suppressed.stream().filter(s -> s.reason().equals("baseline")).count();
        return suppressed.size() + " (" + baseline + " baseline, " + (suppressed.size() - baseline) + " suppress)";
    }
}
