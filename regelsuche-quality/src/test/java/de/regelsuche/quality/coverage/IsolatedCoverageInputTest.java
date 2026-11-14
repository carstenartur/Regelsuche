package de.regelsuche.quality.coverage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import static org.junit.jupiter.api.Assertions.*;

class IsolatedCoverageInputTest {
    private static final Path CANONICAL = Path.of("regelsuche-math-sympy/build/reports/jacoco/test/jacocoTestReport.xml");
    @TempDir Path directory;

    /** Optional Maven entry point for a fresh downloaded CI evidence tree. */
    @Test
    @EnabledIfSystemProperty(named = "regelsuche.coverage.isolatedReport", matches = ".*")
    void verifyRetainedSplitCoverage() {
        String root = System.getProperty("regelsuche.coverage.evidenceRoot",
            System.getProperty("regelsuche.repositoryRoot"));
        assertEquals(0, CoverageRegressionMain.run(new String[]{"--root", root, "--isolated-report",
            System.getProperty("regelsuche.coverage.isolatedReport")}, System.out, System.err));
    }

    @Test void installsExactIsolatedBytesAndEvaluatesTheExistingCoveragePolicy() throws Exception {
        Path root = directory.resolve("root");
        Path isolated = source(root);
        byte[] input = Files.readAllBytes(isolated);
        write(root.resolve("config/quality/coverage-policy.json"), """
            {"schema":"regelsuche.quality.coverage-policy/v1","ratchetPolicy":"fixture",
             "aggregate":{"lineMinimumPercent":90,"branchMinimumPercent":70},
             "modules":{"regelsuche-math-sympy":{"lineMinimumPercent":90,"branchMinimumPercent":70}}}
            """);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = CoverageRegressionMain.run(new String[]{"--root", root.toString(), "--isolated-report",
            root.relativize(isolated).toString()}, new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, err::toString);
        assertArrayEquals(input, Files.readAllBytes(root.resolve(CANONICAL)));
        assertTrue(out.toString().contains("crossAuthorityCoverageSymPyReport=sha256:"
            + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input))));
        assertTrue(out.toString().contains("coverageStatus=PASSED"));
    }

    @Test void missingExternalAndSymbolicEvidenceCannotBeInstalled() throws Exception {
        Path root = Files.createDirectory(directory.resolve("root"));
        Path outside = write(directory.resolve("external.xml"), "untouched");
        Path externalDir = Files.createDirectory(directory.resolve("external"));
        Path link = Files.createSymbolicLink(root.resolve("symlink.xml"), outside);
        Path linkedDir = Files.createSymbolicLink(root.resolve("linked-dir"), externalDir);
        for (Path path : List.of(root.resolve("missing.xml"), outside, link, linkedDir.resolve("missing.xml"), root)) {
            assertThrows(IOException.class, () -> IsolatedCoverageInput.install(root.toRealPath(), path));
            assertFalse(Files.exists(root.resolve(CANONICAL)));
        }
        var input = source(root);
        Files.createSymbolicLink(root.resolve(CANONICAL.getName(0)), externalDir);
        assertThrows(IOException.class, () -> IsolatedCoverageInput.install(root.toRealPath(), input));
        assertEquals(0, externalDir.toFile().list().length);
    }

    @Test void aCompetingOrDanglingCanonicalFileIsNeverReplaced() throws Exception {
        Path root = directory.resolve("root");
        Path input = source(root);
        Path canonical = write(root.resolve(CANONICAL), "separately retained");
        assertThrows(IOException.class, () -> IsolatedCoverageInput.install(root.toRealPath(), input));
        assertEquals("separately retained", Files.readString(canonical));
        Files.delete(canonical);
        Path missing = directory.resolve("missing");
        Files.createSymbolicLink(canonical, missing);
        assertThrows(IOException.class, () -> IsolatedCoverageInput.install(root.toRealPath(), input));
        assertFalse(Files.exists(missing));
        assertTrue(Files.isSymbolicLink(canonical));
    }

    @Test void twoConcurrentInputsCannotReplaceEachOther() throws Exception {
        Path root = directory.resolve("root");
        Path first = source(root);
        Path second = write(root.resolve("incoming/other.xml"), "other measured input");
        var start = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Path>> attempts = List.of(first, second).stream().map(path ->
                workers.submit(() -> {
                    start.await();
                    try {
                        IsolatedCoverageInput.install(root.toRealPath(), path);
                        return path;
                    } catch (IOException occupied) {
                        return (Path) null;
                    }
                })).toList();
            start.countDown();
            var winners = new ArrayList<Path>();
            for (var attempt : attempts) {
                Path path = attempt.get();
                if (path != null) winners.add(path);
            }
            assertEquals(1, winners.size());
            assertEquals(-1, Files.mismatch(winners.getFirst(), root.resolve(CANONICAL)));
        }
    }

    static Path source(Path root) throws Exception {
        return write(root.resolve("incoming/sympy.xml"), """
            <?xml version="1.0"?>
            <report name="retained"><counter type="LINE" missed="1" covered="9"/>
            <counter type="BRANCH" missed="1" covered="3"/></report>
            """);
    }

    static Path write(Path target, String body) throws Exception {
        Files.createDirectories(target.getParent());
        return Files.writeString(target, body);
    }
}
