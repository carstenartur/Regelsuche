package de.regelsuche.quality.jmh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JmhPublishedFilesTest {
    @TempDir Path root;

    @Test void requiresAllNativeFilesBeforePublishingAnyMember() throws Exception {
        Path reports = Files.createDirectory(root.resolve("native"));
        Files.writeString(reports.resolve("allocation.json"), "bytes");
        assertThrows(IOException.class, () -> JmhPublishedFiles.ALLOCATION.publish(root, reports));
        assertFalse(Files.exists(root.resolve("public/dev/bench/allocation.json")));
        Files.createDirectories(reports.resolve("allocation"));
        Files.writeString(reports.resolve("allocation/result.json"), "full raw sample");
        JmhPublishedFiles.ALLOCATION.publish(root, reports);
        assertEquals("bytes", Files.readString(root.resolve("public/dev/bench/allocation.json")));
        assertEquals("full raw sample", Files.readString(root.resolve("app/build/reports/jmh-allocation/result.json")));
    }

    @Test void sendsTheFullProductionInventoryToItsTenEstablishedPaths() throws Exception {
        Path reports = Files.createDirectory(root.resolve("native"));
        for (var entry : JmhPublishedFiles.values()) {
            for (String source : entry.paths().keySet()) {
                Path file = reports.resolve(source);
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.name() + source);
            }
            entry.publish(root, reports);
        }
        var actual = java.util.Arrays.stream(JmhPublishedFiles.values()).flatMap(x -> x.paths().values().stream()).sorted().toList();
        assertEquals(List.of("app/build/reports/jmh-allocation/result.json","app/build/reports/jmh/result.json",
            "build/reports/quality/jmh-regression-report.json","build/reports/quality/jmh-regression-report.md",
            "public/dev/bench/allocation.json","public/dev/bench/badge.json","public/dev/bench/index.html",
            "public/dev/bench/sympy-factorization.html","public/dev/bench/sympy-factorization.json",
            "regelsuche-math-sympy/build/reports/jmh/sympy-factorization.json"), actual);
    }

    @Test void rejectsAnOutputDirectoryRedirectedOutsideTheCheckout() throws Exception {
        Path reports = Files.createDirectory(root.resolve("native"));
        Files.createDirectories(reports.resolve("allocation"));
        Files.writeString(reports.resolve("allocation/result.json"), "raw");
        Files.writeString(reports.resolve("allocation.json"), "summary");
        Path outside = Files.createDirectory(root.getParent().resolve(root.getFileName() + "-outside"));
        try {
            Files.createSymbolicLink(root.resolve("public"), outside);
            assertThrows(IOException.class, () -> JmhPublishedFiles.ALLOCATION.publish(root, reports));
            assertFalse(Files.exists(outside.resolve("dev")));
        } finally { Files.delete(outside); }
    }
}
