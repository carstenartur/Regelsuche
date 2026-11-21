package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeScalarDiagnosticsTest {
    @Test
    void accountsForEveryCaseScheduleAndRegime(@TempDir Path directory) throws Exception {
        Path report = directory.resolve("diagnostics.json");
        ComparativeBenchmarkMain.main(new String[] {
            "--native-scalar-diagnostics", "a".repeat(40), report.toString()
        });
        var result = new JsonMapper().readTree(Files.readString(report));
        assertEquals("regelsuche.native-scalar-diagnostics/v1", result.required("schema").asText());
        assertEquals("a".repeat(40), result.required("sourceRevision").asText());
        assertTrue(result.required("environment").required("javaVersion").isTextual());
        assertTrue(result.required("timeLimitNanos").asLong() > 0);

        var combinations = new HashSet<String>();
        for (var row : result.required("rows")) {
            String id = row.required("case").asText();
            String schedule = row.required("schedule").asText();
            String regime = row.required("regime").asText();
            assertTrue(combinations.add(id + "/" + schedule + "/" + regime));
            assertTrue(row.required("elapsedNanos").asLong() >= 0);
            var allocated = row.required("threadAllocatedBytes");
            assertTrue(allocated.isNull() || allocated.asLong() > 0);
            var graph = row.required("graph");
            assertTrue(graph.required("nodeCount").asInt() > 0);
            int nodes = 0;
            for (var byKind : graph.required("nodesByKind")) {
                nodes += byKind.asInt();
            }
            assertEquals(graph.required("nodeCount").asInt(), nodes);
            assertFalse(graph.required("classesContainingKind").isEmpty());
            verifyMechanicalWork(row, id);
            for (var extraction : row.required("extractions")) {
                assertTrue(extraction.required("proofSteps").asInt() > 0);
                assertTrue(extraction.required("proofPremiseEdges").asInt() >= 0);
                assertTrue(extraction.required("proofDepth").asInt() > 0);
                assertTrue(extraction.required("proofDepth").asInt() <= extraction.required("proofSteps").asInt());
                if (id.equals("unknown-cancellation")) {
                    assertEquals(1, extraction.required("proofDepth").asInt());
                }
                if (id.equals("mixed-conditional-zero")) {
                    assertEquals(7, extraction.required("proofDepth").asInt());
                }
                assertTrue(extraction.required("detachedReplayWork").asLong() > 0);
            }
            boolean withinDeadline = regime.equals("FIXED_WORK")
                    || row.required("elapsedNanos").asLong() <= result.required("timeLimitNanos").asLong();
            assertEquals(withinDeadline, row.required("withinBudget").asBoolean());
            var firstRound = row.required("firstCheckedRound");
            if (id.equals("unknown-cancellation")) {
                assertTrue(firstRound.isNull());
            } else if (regime.equals("FIXED_WORK")) {
                assertEquals(1, firstRound.asInt());
            } else if (row.required("reached").asBoolean()) {
                assertTrue(firstRound.isIntegralNumber());
                assertTrue(firstRound.asInt() >= 1 && firstRound.asInt() <= row.required("rounds").asInt());
            }
            if (regime.equals("FIXED_WORK")) {
                assertEquals(!id.equals("unknown-cancellation"), row.required("reached").asBoolean());
            } else if (!withinDeadline) {
                assertTrue(row.required("reached").isNull());
            }
        }
        assertEquals(20, combinations.size());
        assertTrue(combinations.containsAll(Set.of(
                "factor-common-left/ROUND_ROBIN/FIXED_WORK",
                "factor-common-right/PRODUCTIVITY/FIXED_TIME")));
    }

    private static void verifyMechanicalWork(com.fasterxml.jackson.databind.JsonNode row, String id) {
        var work = row.required("work");
        var producer = work.required("producerStages");
        long nativeTotal = work.required("matchingFuel").asLong() + work.required("analysisEvaluations").asLong();
        for (String stage : Set.of("sourcePreparation", "scheduler", "graphStructure",
                "representativeForest", "extraction", "proofProduction")) {
            long units = producer.required(stage).asLong();
            assertTrue(units > 0, stage);
            nativeTotal += units;
        }
        long guardChecks = producer.required("guardChecks").asLong();
        assertTrue(guardChecks >= 0);
        assertEquals(nativeTotal + guardChecks, work.required("totalMechanicalUnits").asLong());
        assertTrue(work.required("proofReplayWork").asLong() > 0);
        if (id.equals("unknown-cancellation")) {
            assertTrue(guardChecks > 0);
            assertEquals(0, work.required("ruleMerges").asLong());
        }
    }
}
