package io.themis.source;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Walks a project's own files, skipping build output, VCS and IDE directories, dependencies,
 * excluded globs and anything over 1 MB. Paths are reported relative to the project root.
 */
public final class SourceFiles {

    public record SourceFile(Path absolute, Path relative) {
        public boolean isTest() {
            String r = relative.toString().replace('\\', '/');
            return r.contains("src/test/") || r.contains("/test/") || r.startsWith("test/")
                    || r.contains("src/testFixtures/") || r.contains("src/integrationTest/") || r.endsWith("Test.java")
                    || r.endsWith("Tests.java") || r.endsWith("IT.java");
        }

        public String name() {
            return relative.getFileName().toString();
        }
    }

    static final Set<String> SKIPPED_DIRS = Set.of(".git", ".hg", ".svn", "target", "build", "out", "node_modules",
            ".gradle", ".idea", ".vscode", ".mvn", "dist", ".venv", "venv", "__pycache__", "themis-report", ".themis");
    static final long MAX_BYTES = 1024 * 1024;

    private SourceFiles() {
    }

    public static List<SourceFile> list(Path root, Set<String> excludeGlobs, Predicate<String> nameFilter) throws IOException {
        List<PathMatcher> excludes = excludeGlobs.stream()
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        List<SourceFile> out = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SKIPPED_DIRS.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                Path rel = root.relativize(dir);
                return !dir.equals(root) && excludes.stream().anyMatch(m -> m.matches(rel))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || attrs.size() > MAX_BYTES) return FileVisitResult.CONTINUE;
                Path rel = root.relativize(file);
                if (excludes.stream().anyMatch(m -> m.matches(rel))) return FileVisitResult.CONTINUE;
                if (nameFilter.test(file.getFileName().toString().toLowerCase(Locale.ROOT))) out.add(new SourceFile(file, rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;   // unreadable file: skip it, never fail the scan
            }
        });
        out.sort((a, b) -> a.relative().toString().compareTo(b.relative().toString()));
        return out;
    }

    /** True if the line, or the line before it, carries a {@code themis:ignore} marker. */
    public static boolean ignored(List<String> lines, int lineNumber) {
        int i = lineNumber - 1;
        return (i >= 0 && i < lines.size() && lines.get(i).contains("themis:ignore"))
                || (i - 1 >= 0 && i - 1 < lines.size() && lines.get(i - 1).trim().matches("(#|//|<!--|--|;).*themis:ignore.*"));
    }
}
