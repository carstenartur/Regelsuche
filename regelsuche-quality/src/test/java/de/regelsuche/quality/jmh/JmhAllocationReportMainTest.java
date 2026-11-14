package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmhAllocationReportMainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SEARCH = "de.regelsuche.benchmark.PreparedAstSearchBenchmarks.";
    private static final String REWRITE = "de.regelsuche.benchmark.RewriteProgramBenchmarks.";
    private static final String GC_METRIC = "·gc.alloc.rate.norm";
    private static final List<String> REQUIRED = List.of(
        REWRITE + "directAstRewriteSource",
        REWRITE + "preparedAstRewriteSource",
        SEARCH + "referenceFixedWorkSearch",
        SEARCH + "preparedFixedWorkSearch",
        SEARCH + "referenceTargetedSearch",
        SEARCH + "preparedTargetedSearch");
    @TempDir Path directory;

    @Test
    void retainsTheScopedInventoryOrderAndOptionalCounters() throws Exception {
        var rows = sample();
        rows.addObject().put("benchmark", "unrelated");
        rows.addObject().put("benchmark", "unrelated");
        Path input = write(rows);
        Path output = directory.resolve("report/allocation.json");
        JmhAllocationReportMain.main(new String[] {input.toString(), output.toString()});
        String first = Files.readString(output);
        var report = JSON.readTree(first);
        assertEquals("regelsuche.jmh-allocation-summary/v1", report.path("schema").asText());
        assertEquals("ms/op", report.path("primaryUnit").asText());
        assertEquals("B/op", report.path("allocationUnit").asText());
        var results = report.path("benchmarks");
        assertEquals(REQUIRED.size(), results.size());
        for (int index = 0; index < REQUIRED.size(); index++) {
            var row = results.get(index);
            assertEquals(REQUIRED.get(index), row.path("benchmark").asText());
            assertEquals(0.125 + index, row.path("millisecondsPerOperation").asDouble());
            assertEquals(12_345_678.5 + index, row.path("bytesPerOperation").asDouble());
        }
        assertTrue(results.get(0).path("workCounters").isEmpty());
        assertEquals(17.0, results.get(5).path("workCounters").path("searches").asDouble());
        assertEquals(0.0, results.get(5).path("workCounters").path("reachedTargets").asDouble());
        assertTrue(first.contains("\"bytesPerOperation\": 12345678.5"));
        JmhAllocationReportMain.main(new String[] {input.toString(), output.toString()});
        assertEquals(first, Files.readString(output));
    }

    @Test
    void keepsScientificNotationAndSignedZeroInCanonicalEvidence() throws Exception {
        var rows = sample();
        var cases = List.of(-0.0, 0.0001, 0.00001, 1_000_000_000_000_000.0, 1e16, 1.25e98);
        var expected = List.of("-0.0", "0.0001", "1e-05", "1000000000000000.0", "1e+16", "1.25e+98");
        for (int index = 0; index < cases.size(); index++) {
            ((ObjectNode) rows.get(index).get("primaryMetric")).put("score", cases.get(index));
        }
        ((ObjectNode) rows.get(0).at("/secondaryMetrics/" + GC_METRIC)).put("score", Double.MIN_VALUE);
        Path output = directory.resolve("report.json");
        JmhAllocationReportMain.main(new String[] {write(rows).toString(), output.toString()});
        String report = Files.readString(output);
        assertTrue(report.contains("\"bytesPerOperation\": 5e-324,"), report);
        for (String literal : expected) {
            assertTrue(report.contains("\"millisecondsPerOperation\": " + literal + ","), report);
        }
    }

    @Test
    void rejectsMissingDuplicateAndInvalidMetricsBeforeReplacingTheReport() throws Exception {
        var missing = sample();
        missing.remove(0);
        rejects(missing, "required allocation benchmarks missing");

        var duplicate = sample();
        duplicate.add(duplicate.get(0).deepCopy());
        rejects(duplicate, "duplicate JMH allocation benchmark");

        var unit = sample();
        ((ObjectNode) unit.get(0).get("primaryMetric")).put("scoreUnit", "us/op");
        rejects(unit, "expected primary unit ms/op");

        var allocationUnit = sample();
        ((ObjectNode) allocationUnit.get(0).get("secondaryMetrics").get(GC_METRIC)).put("scoreUnit", "MB/sec");
        rejects(allocationUnit, "unit B/op");

        var score = sample();
        ((ObjectNode) score.get(0).get("secondaryMetrics").get(GC_METRIC)).put("score", -1);
        rejects(score, "finite and non-negative");

        var counter = sample();
        ((ObjectNode) counter.get(0).get("secondaryMetrics").get("searches")).put("score", "NaN");
        rejects(counter, "must be numeric");
    }

    @Test
    void preservesTheRawResultWhenItIsAlsoTheRequestedOutput() throws Exception {
        Path input = write(sample());
        byte[] before = Files.readAllBytes(input);
        assertThrows(IllegalArgumentException.class,
            () -> JmhAllocationReportMain.main(new String[] {input.toString(), input.toString()}));
        assertArrayEquals(before, Files.readAllBytes(input));
    }

    private void rejects(ArrayNode rows, String diagnostic) throws Exception {
        Path input = write(rows);
        Path output = Files.writeString(directory.resolve("previous.json"), "retained evidence");
        var failure = assertThrows(IllegalArgumentException.class,
            () -> JmhAllocationReportMain.main(new String[] {input.toString(), output.toString()}));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
        assertEquals("retained evidence", Files.readString(output));
    }

    private Path write(ArrayNode rows) throws Exception {
        return Files.writeString(directory.resolve("result.json"), JSON.writeValueAsString(rows));
    }

    private static ArrayNode sample() {
        var rows = JSON.createArrayNode();
        for (int index = REQUIRED.size() - 1; index >= 0; index--) {
            var row = rows.addObject().put("benchmark", REQUIRED.get(index));
            row.putObject("primaryMetric").put("scoreUnit", "ms/op").put("score", 0.125 + index);
            var secondary = row.putObject("secondaryMetrics");
            secondary.putObject(GC_METRIC).put("scoreUnit", "B/op").put("score", 12_345_678.5 + index);
            if (index == 5) {
                secondary.putObject("searches").put("score", 17);
                secondary.putObject("reachedTargets").put("score", 0);
            }
        }
        return rows;
    }
}
