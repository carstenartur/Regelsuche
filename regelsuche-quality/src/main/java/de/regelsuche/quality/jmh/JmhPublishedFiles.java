package de.regelsuche.quality.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** The same raw measurements and original decision bytes consumed by #514 and Pages. */
public enum JmhPublishedFiles {
    LATENCY(Map.of(
        "latency/result.json", "app/build/reports/jmh/result.json",
        "jmh-regression-report.json", "build/reports/quality/jmh-regression-report.json",
        "jmh-regression-report.md", "build/reports/quality/jmh-regression-report.md",
        "badge.json", "public/dev/bench/badge.json",
        "index.html", "public/dev/bench/index.html")),
    ALLOCATION(Map.of(
        "allocation/result.json", "app/build/reports/jmh-allocation/result.json",
        "allocation.json", "public/dev/bench/allocation.json")),
    FACTORIZATION(Map.of(
        "sympy-factorization.json", "regelsuche-math-sympy/build/reports/jmh/sympy-factorization.json",
        "summary.json", "public/dev/bench/sympy-factorization.json",
        "index.html", "public/dev/bench/sympy-factorization.html"));

    private final Map<String, String> paths;
    JmhPublishedFiles(Map<String, String> paths) { this.paths = paths; }
    Map<String, String> paths() { return paths; }

    public void publish(Path root, Path nativeReports) throws IOException {
        for (var row : paths.entrySet()) {
            Path source = nativeReports.resolve(row.getKey());
            Path dest = root.resolve(row.getValue());
            rejectLinks(source);
            rejectLinks(dest);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) == 0) {
                throw new IOException("Missing native benchmark evidence: " + source);
            }
        }
        for (var row : paths.entrySet()) {
            Path output = root.resolve(row.getValue());
            Files.createDirectories(output.getParent());
            Path temp = Files.createTempFile(output.getParent(), ".jmh-", ".tmp");
            try {
                Files.copy(nativeReports.resolve(row.getKey()), temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("Symbolic link in benchmark path: " + p);
        }
    }
}
