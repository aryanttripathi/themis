package io.themis.cli;

import io.themis.Themis;
import io.themis.config.ConfigFile;
import io.themis.config.ConfigLoader;
import io.themis.model.ScanReport;
import io.themis.report.HtmlReportWriter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Console vs HTML: when a command would print more rows than {@code report.consoleLimit}
 * (default {@value #DEFAULT_LIMIT}, {@code --console-limit} wins), the console gets the summary
 * only and the full result goes to a self-contained {@code report.html}, opened in the browser
 * when running interactively (never in CI or with {@code --no-open} / {@code report.open: false}).
 */
final class ReportOutput {

    static final int DEFAULT_LIMIT = 25;

    record Settings(int consoleLimit, boolean open) {
    }

    private ReportOutput() {
    }

    static Settings settings(Path projectDir, Path configFile, Integer cliLimit, boolean noOpen) {
        Optional<ConfigFile> file = ConfigLoader.load(projectDir, Optional.ofNullable(configFile), Themis.analyzerNames());
        int limit = cliLimit != null ? cliLimit
                : file.flatMap(ConfigFile::consoleLimit).orElse(DEFAULT_LIMIT);
        boolean open = !noOpen && file.flatMap(ConfigFile::openReport).orElse(true);
        return new Settings(limit, open);
    }

    /** Writes report.html, prints its path, opens it if allowed. */
    static Path writeHtml(ScanReport report, Path outputDir, boolean open, PrintWriter out) throws IOException {
        Path html = new HtmlReportWriter().write(report, outputDir).toAbsolutePath();
        out.println("HTML report: " + html);
        out.flush();
        if (open) openInBrowser(html);
        return html;
    }

    static void tooMany(PrintWriter out, int rows, String what, int limit) {
        out.println(rows + " " + what + " exceeds the console limit of " + limit
                + " (--console-limit / report.consoleLimit): full details are in the HTML report.");
    }

    /** Only for an interactive terminal outside CI; failures are ignored (the path is printed anyway). */
    static void openInBrowser(Path html) {
        if (System.console() == null || System.getenv("CI") != null) return;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> cmd = os.contains("mac") ? List.of("open", html.toString())
                : os.contains("win") ? List.of("cmd", "/c", "start", "", html.toString())
                : List.of("xdg-open", html.toString());
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ignored) {
            // no browser available: the printed path is enough
        }
    }
}
