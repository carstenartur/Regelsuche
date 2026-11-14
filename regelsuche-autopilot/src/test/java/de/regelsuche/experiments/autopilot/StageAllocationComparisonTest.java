package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StageAllocationComparisonTest {
    @TempDir Path temporary;

    @Test
    void productionEntryPointReplaysAllSixRegisteredLanesByteForByte() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("research/benchmarks/track-f-stage-allocation"))) {
            root = root.getParent();
        }
        Path evidence = root.resolve("research/benchmarks/track-f-stage-allocation");
        Path output = temporary.resolve("run.json");
        AutonomousProductionCampaignMain.main(new String[] {
            "--compare-stage-allocation", evidence.resolve("plan.json").toString(), output.toString()});
        try (var gz = new GZIPInputStream(Files.newInputStream(evidence.resolve("measured.json.gz")))) {
            assertArrayEquals(gz.readAllBytes(), Files.readAllBytes(output));
        }
        var lanes = new JsonMapper().readTree(Files.readAllBytes(output)).required("lanes");
        assertEquals(6, lanes.size());
        assertEquals("FORMED", lanes.get(1).at("/candidates/0/status").asText());
        assertEquals("COMPLETED", lanes.get(2).at("/candidates/0/status").asText());
        assertEquals(2, lanes.get(5).required("candidates").size());
    }

    @Test
    void rejectsChangedPlanAndDoesNotSplitTheJointEvaluatorAcrossTickets() throws Exception {
        Path changed = temporary.resolve("changed.json");
        Files.writeString(changed, "{}");
        assertThrows(IllegalArgumentException.class, () -> StageAllocationComparison.write(changed, temporary.resolve("out")));
        var run = new AutonomousProductionMiningRunner().runPinned(1);
        var candidates = ProductionCandidateContinuation.fromBatch(0, run.fullBatch(), run.generation().brief().deterministicSeed());
        var stages = new java.util.ArrayList<String>();
        ProductionCandidateContinuation.DispatchSink sink =
            (stage, batch, id, hash, resource, work) -> stages.add(stage.name());
        assertEquals(0, AutonomousCampaignFeedback.continueEligible(candidates, 1, sink));
        assertEquals(2, AutonomousCampaignFeedback.continueEligible(candidates, 2, sink));
        assertEquals(List.of("VALIDATION", "COUNTEREXAMPLE_SEARCH"), stages);
        assertEquals(1, AutonomousCampaignFeedback.continueEligible(candidates, 1, sink));
        assertEquals("PROJECT_NOVELTY", stages.getLast());
    }
}
