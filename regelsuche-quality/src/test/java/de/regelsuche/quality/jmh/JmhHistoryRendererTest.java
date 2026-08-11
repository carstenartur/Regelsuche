package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmhHistoryRendererTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void rendersDeterministicNormalizedJsonMarkdownAndSvg() throws Exception {
        Fixture fixture = fixture();
        Path firstOutput = temporaryDirectory.resolve("first-output");
        Path secondOutput = temporaryDirectory.resolve("second-output");
        Path staleChart = firstOutput.resolve("charts/stale.svg");
        Files.createDirectories(staleChart.getParent());
        Files.writeString(staleChart, "stale");

        JmhHistory history = new JmhHistoryLoader().load(
            fixture.historyPolicy(),
            fixture.regressionPolicy()
        );
        new JmhHistoryReportWriter().write(history, firstOutput);
        new JmhHistoryReportWriter().write(history, secondOutput);

        assertEquals(2, history.snapshots().size());
        assertEquals(2, history.benchmarks().size());
        assertFalse(Files.exists(staleChart));
        assertEquals(treeDigests(firstOutput), treeDigests(secondOutput));

        JsonNode report = mapper.readTree(
            firstOutput.resolve("history.json").toFile()
        );
        assertEquals("PASSED", report.path("status").textValue());
        JsonNode fast = benchmark(report, "example.Fast.us");
        assertEquals(
            1.0d,
            fast.path("points").get(0).path("scoreMsPerOp").doubleValue()
        );
        assertEquals(
            0.8d,
            fast.path("points").get(1).path("scoreMsPerOp").doubleValue()
        );
        String chart = Files.readString(
            firstOutput.resolve(fast.path("chart").textValue())
        );
        assertTrue(chart.contains("lower is faster/better"));
        assertTrue(chart.contains("error bars show JMH scoreError"));
        assertTrue(Files.readString(firstOutput.resolve("history.md"))
            .contains("Lower values and lower chart points are faster/better"));
    }

    @Test
    void rejectsSnapshotDigestDrift() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(
            fixture.firstSnapshot(),
            " ",
            StandardOpenOption.APPEND
        );

        JmhHistoryLoader.HistoryException failure = assertThrows(
            JmhHistoryLoader.HistoryException.class,
            () -> load(fixture)
        );
        assertTrue(failure.getMessage().contains("digest mismatch"));
    }

    @Test
    void rejectsMissingBenchmark() throws Exception {
        Fixture fixture = fixture();
        ObjectNode second = object(fixture.secondSnapshot());
        ((ArrayNode) second.path("benchmarks")).remove(1);
        rewriteSnapshotAndPolicy(fixture, second);

        JmhHistoryLoader.HistoryException failure = assertThrows(
            JmhHistoryLoader.HistoryException.class,
            () -> load(fixture)
        );
        assertTrue(failure.getMessage().contains("inventory differs"));
    }

    @Test
    void rejectsNonChronologicalSnapshots() throws Exception {
        Fixture fixture = fixture();
        ObjectNode second = object(fixture.secondSnapshot());
        second.put("recordedAt", "2025-12-01T00:00:00Z");
        rewriteSnapshotAndPolicy(fixture, second);

        JmhHistoryLoader.HistoryException failure = assertThrows(
            JmhHistoryLoader.HistoryException.class,
            () -> load(fixture)
        );
        assertTrue(failure.getMessage().contains("strictly chronological"));
    }

    @Test
    void rejectsUnitDriftAgainstRegressionContract() throws Exception {
        Fixture fixture = fixture();
        ObjectNode second = object(fixture.secondSnapshot());
        ((ObjectNode) second.path("benchmarks").get(0))
            .put("unit", "ms/op");
        rewriteSnapshotAndPolicy(fixture, second);

        JmhHistoryLoader.HistoryException failure = assertThrows(
            JmhHistoryLoader.HistoryException.class,
            () -> load(fixture)
        );
        assertTrue(failure.getMessage().contains("unit differs"));
    }

    @Test
    void rejectsCollidingChartFileNamesBeforeWritingCharts() {
        Path output = temporaryDirectory.resolve("collision-output");

        IOException failure = assertThrows(
            IOException.class,
            () -> new JmhHistoryReportWriter().write(
                collidingHistory(),
                output
            )
        );

        assertTrue(failure.getMessage().contains("filename collision"));
        assertFalse(Files.exists(output.resolve("charts")));
    }

    @Test
    void retainedRepositoryHistoryIsCompleteAndRenderable() throws Exception {
        Path root = repositoryRoot();
        JmhHistory history = new JmhHistoryLoader().load(
            root.resolve("config/quality/jmh-history-policy.json"),
            root.resolve("config/quality/jmh-regression-policy-v2.json")
        );
        Path output = temporaryDirectory.resolve("repository-output");
        new JmhHistoryReportWriter().write(history, output);

        assertEquals(2, history.snapshots().size());
        assertEquals(29, history.benchmarks().size());
        try (var charts = Files.list(output.resolve("charts"))) {
            assertEquals(29, charts.filter(Files::isRegularFile).count());
        }
        JsonNode report = mapper.readTree(output.resolve("history.json").toFile());
        assertEquals(29, report.path("benchmarkCount").intValue());
        assertTrue(report.path("historyPolicyDigest").textValue()
            .matches("sha256:[0-9a-f]{64}"));
    }

    private JmhHistory load(Fixture fixture) throws IOException {
        return new JmhHistoryLoader().load(
            fixture.historyPolicy(),
            fixture.regressionPolicy()
        );
    }

    private Fixture fixture() throws Exception {
        Path root = temporaryDirectory.resolve("fixture-repository");
        Path quality = root.resolve("config/quality");
        Path history = quality.resolve("jmh-history");
        Files.createDirectories(history);
        Path first = history.resolve("first.json");
        Path second = history.resolve("second.json");
        writeJson(first, snapshot(
            "first",
            "2026-01-01T00:00:00Z",
            "a".repeat(40),
            1000.0d,
            4.0d
        ));
        writeJson(second, snapshot(
            "second",
            "2026-02-01T00:00:00Z",
            "b".repeat(40),
            800.0d,
            3.0d
        ));

        Path regression = quality.resolve("jmh-regression-policy-v2.json");
        writeJson(regression, Map.of("benchmarks", List.of(
            Map.of(
                "benchmark", "example.Fast.us",
                "family", "CORE",
                "unit", "us/op"
            ),
            Map.of(
                "benchmark", "example.Search.ms",
                "family", "SEARCH",
                "unit", "ms/op"
            )
        )));
        Path policy = quality.resolve("jmh-history-policy.json");
        writePolicy(policy, first, second);
        return new Fixture(policy, regression, first, second);
    }

    private JmhHistory collidingHistory() {
        Map<String, JmhHistory.BenchmarkContract> contracts =
            new LinkedHashMap<>();
        contracts.put(
            "example.A B",
            new JmhHistory.BenchmarkContract("CORE", "ms/op")
        );
        contracts.put(
            "example.A+B",
            new JmhHistory.BenchmarkContract("CORE", "ms/op")
        );
        Map<String, JmhHistory.Measurement> measurements =
            new LinkedHashMap<>();
        measurements.put(
            "example.A B",
            new JmhHistory.Measurement(1.0d, 0.1d)
        );
        measurements.put(
            "example.A+B",
            new JmhHistory.Measurement(2.0d, 0.2d)
        );
        JmhHistory.Snapshot snapshot = new JmhHistory.Snapshot(
            "collision",
            "2026-01-01T00:00:00Z",
            "a".repeat(40),
            "sha256:" + "1".repeat(64),
            "collision.json",
            "sha256:" + "2".repeat(64),
            measurements
        );
        return new JmhHistory(
            "synthetic collision fixture",
            "sha256:" + "3".repeat(64),
            "sha256:" + "4".repeat(64),
            List.of(snapshot),
            contracts
        );
    }

    private Map<String, Object> snapshot(
        String label,
        String recordedAt,
        String revision,
        double fastScore,
        double searchScore
    ) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schema", JmhHistoryLoader.SNAPSHOT_SCHEMA);
        document.put("label", label);
        document.put("recordedAt", recordedAt);
        document.put("sourceRevision", revision);
        document.put("sourceArtifactDigest", "sha256:" + "1".repeat(64));
        document.put("execution", Map.of(
            "mode", "avgt",
            "forks", 1,
            "threads", 1,
            "warmupIterations", 2,
            "measurementIterations", 3,
            "jmhVersion", "1.36",
            "jdkMajor", 21
        ));
        document.put("benchmarks", List.of(
            Map.of(
                "benchmark", "example.Fast.us",
                "family", "CORE",
                "unit", "us/op",
                "score", fastScore,
                "scoreError", 0.2d
            ),
            Map.of(
                "benchmark", "example.Search.ms",
                "family", "SEARCH",
                "unit", "ms/op",
                "score", searchScore,
                "scoreError", 0.1d
            )
        ));
        return document;
    }

    private void rewriteSnapshotAndPolicy(
        Fixture fixture,
        JsonNode snapshot
    ) throws Exception {
        writeJson(fixture.secondSnapshot(), snapshot);
        replaceSecondDigest(fixture);
    }

    private void replaceSecondDigest(Fixture fixture) throws Exception {
        ObjectNode policy = object(fixture.historyPolicy());
        ((ObjectNode) policy.path("snapshots").get(1)).put(
            "sha256",
            digest(fixture.secondSnapshot())
        );
        writeJson(fixture.historyPolicy(), policy);
    }

    private void writePolicy(
        Path policy,
        Path first,
        Path second
    ) throws Exception {
        writeJson(policy, Map.of(
            "schema", JmhHistoryLoader.POLICY_SCHEMA,
            "normalizedUnit", "ms/op",
            "lowerIsBetter", true,
            "claimBoundary", "synthetic fixture",
            "snapshots", List.of(
                Map.of(
                    "path", "config/quality/jmh-history/first.json",
                    "sha256", digest(first)
                ),
                Map.of(
                    "path", "config/quality/jmh-history/second.json",
                    "sha256", digest(second)
                )
            )
        ));
    }

    private ObjectNode object(Path path) throws IOException {
        return (ObjectNode) mapper.readTree(path.toFile());
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(value);
        Files.write(path, newlineTerminated(bytes));
    }

    private Map<String, String> treeDigests(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString(), digest(path));
            }
        }
        return Map.copyOf(result);
    }

    private static JsonNode benchmark(JsonNode report, String name) {
        for (JsonNode benchmark : report.path("benchmarks")) {
            if (name.equals(benchmark.path("benchmark").textValue())) {
                return benchmark;
            }
        }
        throw new AssertionError("benchmark missing from report: " + name);
    }

    private static Path repositoryRoot() {
        String configured = System.getProperty("regelsuche.repositoryRoot");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("pom.xml"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("repository root not found");
        }
        return current;
    }

    private static String digest(Path path) throws Exception {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] newlineTerminated(byte[] value) {
        byte[] result = new byte[value.length + 1];
        System.arraycopy(value, 0, result, 0, value.length);
        result[result.length - 1] = '\n';
        return result;
    }

    private record Fixture(
        Path historyPolicy,
        Path regressionPolicy,
        Path firstSnapshot,
        Path secondSnapshot
    ) {
    }
}
