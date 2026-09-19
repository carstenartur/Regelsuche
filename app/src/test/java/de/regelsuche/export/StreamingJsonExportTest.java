package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import de.regelsuche.mining.RuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamingJsonExportTest {
    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    @Test
    void fileExportRetainsEveryCandidateWhenJsonExceedsAvailableHeap(@TempDir Path root) throws Exception {
        Path log = root.resolve("child.log");
        Process process = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx32m", "-cp", childClasspath(), Child.class.getName(), root.toString())
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "bounded-heap export timed out");
            assertEquals(0, process.exitValue(), () -> read(log));
            assertTrue(Files.size(root.resolve("discovered-transformations.json")) > 64L * 1024 * 1024);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    @Test
    void fileAndStringExportsHaveIdenticalUtf8Contents(@TempDir Path root) throws Exception {
        var service = new DefaultTransformationExportService(CLOCK);
        var candidates = List.of(candidate("B ∈ {u, \"v\"}\\\n\t\b\f\r\u0001😀"));
        new ExportFileService(service).writeAll(root, List.of("json", "inventory"),
            List.of(), candidates, List.of());
        assertEquals(service.exportJson(List.of(), candidates, List.of()),
            Files.readString(root.resolve("discovered-transformations.json")));
        assertEquals(service.exportJson(List.of(), List.of(), List.of()),
            Files.readString(root.resolve("rule-inventory.json")));
    }

    @Test
    void failedStreamDoesNotReplaceAnExistingExportOrLeaveTemporaryFiles(@TempDir Path root) throws Exception {
        Path output = root.resolve("discovered-transformations.json");
        Files.writeString(output, "previous complete export");
        var cause = new java.io.IOException("disk failure");
        var service = new DefaultTransformationExportService(CLOCK) {
            @Override public void writeJson(java.io.Writer destination,
                    List<de.regelsuche.discovery.DiscoveredTransformation> transformations,
                    List<RuleCandidate> candidates, List<de.regelsuche.inventory.ReusableRule> rules)
                    throws java.io.IOException {
                destination.write("{partial");
                throw cause;
            }
        };
        assertSame(cause, assertThrows(java.io.IOException.class, () -> new ExportFileService(service)
            .writeAll(root, List.of("json"), List.of(), List.of(), List.of())));
        assertEquals("previous complete export", Files.readString(output));
        try (var files = Files.list(root)) { assertEquals(List.of(output), files.toList()); }
    }

    private static RuleCandidate candidate(String description) {
        return new RuleCandidate("B*B", "B^2", 3, 1.0, 1, false, true, true,
            List.of(description), RuleStatus.NEW, CandidateProofStatus.OBSERVED, "test-only", List.of("witness"));
    }

    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (java.io.IOException failure) { return failure.toString(); }
    }

    private static String childClasspath() {
        var entries = new LinkedHashSet<String>();
        entries.add(System.getProperty("java.class.path"));
        // Gradle loads test/runtime dependencies outside the worker's java.class.path.
        for (ClassLoader loader = Child.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (var url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) {
                        try { entries.add(Path.of(url.toURI()).toString()); }
                        catch (java.net.URISyntaxException bad) { throw new IllegalStateException(bad); }
                    }
                }
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    public static final class Child {
        public static void main(String[] args) throws Exception {
            String annotation = "x∈y".repeat(4096);
            int count = 4096;
            Path root = Path.of(args[0]);
            new ExportFileService(new DefaultTransformationExportService(CLOCK)).writeAll(
                root, List.of("json"), List.of(), Collections.nCopies(count, candidate(annotation)), List.of());
            int observed = 0;
            // Independently parse the complete file with bounded memory, rather than checking just its size.
            try (var parser = new com.fasterxml.jackson.core.JsonFactory()
                    .createParser(root.resolve("discovered-transformations.json").toFile())) {
                while (parser.nextToken() != null) {
                    if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.FIELD_NAME
                            && parser.currentName().equals("parameterRelations")) {
                        if (parser.nextToken() != com.fasterxml.jackson.core.JsonToken.START_ARRAY
                                || parser.nextToken() != com.fasterxml.jackson.core.JsonToken.VALUE_STRING
                                || !annotation.equals(parser.getText())
                                || parser.nextToken() != com.fasterxml.jackson.core.JsonToken.END_ARRAY) {
                            throw new AssertionError("candidate annotation changed or truncated");
                        }
                        observed++;
                    }
                }
            }
            if (observed != count) throw new AssertionError("missing candidates: " + observed);
        }
    }
}
