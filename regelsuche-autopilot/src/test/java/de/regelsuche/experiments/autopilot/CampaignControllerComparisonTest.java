package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CampaignControllerComparisonTest {
    @TempDir
    Path temporary;

    @Test
    void executesThreeIndependentSchedulesWithMatchedInputsAndBalancedWork() throws Exception {
        var file = temporary.resolve("matrix.json");
        AutonomousProductionCampaignMain.main(new String[]{"--compare-controllers", file.toString()});
        var matrix = new JsonMapper().readTree(Files.readAllBytes(file));
        var lanes = matrix.required("lanes");
        assertEquals(3, lanes.size());
        String brief = matrix.required("brief").required("contentHash").textValue();
        Set<String> configurations = new HashSet<>();
        var controllers = List.of("PRODUCTION_FEEDBACK", "FIXED_UNIFORM", "NO_FEEDBACK");
        for (int i = 0; i < lanes.size(); i++) {
            var lane = lanes.get(i);
            assertEquals("COMPLETED", lane.required("status").textValue(), () -> lane.path("failure").toString());
            assertEquals(controllers.get(i), lane.required("policy").textValue());
            assertTrue(configurations.add(lane.required("configurationHash").textValue()));
            assertEquals(brief, lane.required("generation").required("briefHash").textValue());
            assertEquals(lanes.get(0).required("generation"), lane.required("generation"));
            assertEquals(lanes.get(0).required("candidateIds"), lane.required("candidateIds"));
            assertEquals(1, lane.required("candidateIds").size());
            assertEquals(lane.at("/lifecycle/conjectureId"), lane.at("/candidateIds/0"));
            assertEquals(lanes.get(0).required("seedCatalog"), lane.required("seedCatalog"));
            assertFalse(lane.required("generation").required("targetProvided").booleanValue());
            assertEquals(12, lane.required("evidenceDag").required("observations").size());
            assertEquals(2, lane.required("evidenceDag").required("decisions").size());
            assertEquals(2, lane.required("evidenceDag").required("receipts").size());
            assertEquals(12, lane.at("/branches/0/decision/inputs").size());
            assertEquals(2, lane.at("/branches/1/decision/inputs").size());
            assertEquals(1, lane.at("/branches/0/receipt/outputs").size());
            assertEquals(2, lane.required("branches").size());
            assertEquals(0, lane.at("/branches/1/receipt/outputs").size());
            assertEquals("alpha-distinct-support<2",
                lane.at("/branches/1/receipt/rejectedClusters/0/reasonCode").textValue());
            for (var branch : lane.required("branches")) {
                var decision = branch.required("decision");
                var receipt = branch.required("receipt");
                Set<String> families = new HashSet<>();
                for (var input : decision.required("inputs")) {
                    families.add(input.required("familyId").textValue());
                }
                assertEquals(2, families.size());
                assertEquals(decision.required("contentHash"), receipt.required("decisionHash"));
                assertEquals(receipt.required("contentHash"), branch.at("/execution/aggregateReceiptHash"));
                assertEquals(lane.at("/plan/contentHash"), branch.at("/execution/planHash"));
            }
            assertEquals(i == 1 ? 6 : 11, candidateCapacity(lane.at("/branches/0/decision")));
            assertEquals(i == 1 ? 6 : 1, candidateCapacity(lane.at("/branches/1/decision")));
            assertEquals(i == 0, lane.has("feedback"));
            assertEquals(i == 0 ? "CAMPAIGN_COMPLETE" : "FIXED_ONE_ROUND_HORIZON", lane.required("stopReason").textValue());
            assertEquals(0, lane.required("nextPlan").required("decisions").size());
            if (i == 0) {
                assertEquals(0, lane.required("feedback").required("eligibleBranchIds").size());
                assertEquals(lane.at("/nextPlan/contentHash"), lane.at("/feedback/nextPlanHash"));
            }
            assertEquals("ACCEPTED_FOR_PROOF", lane.at("/validation/status").textValue());
            assertTrue(lane.required("validation").required("allHoldoutsPassed").booleanValue());
            assertEquals("NO_COUNTEREXAMPLE_FOUND", lane.at("/counterexample/status").textValue());
            assertEquals("NOVEL_WITHIN_PROJECT", lane.at("/novelty/status").textValue());
            assertEquals("SYMBOLICALLY_VERIFIED", lane.at("/proof/proofStatus").textValue());
            assertEquals("CONFIRMED", lane.at("/solverResult/status").textValue());
            assertEquals("LOSSLESS", lane.at("/solverResult/translationStatus").textValue());
            assertEquals(lane.at("/solverObligation/contentHash"), lane.at("/solverResult/obligationHash"));
            assertEquals(lane.at("/solverResult/contentHash"), lane.at("/proof/solverResultHash"));
            assertEquals("COMPLETED", lane.at("/lifecycleDecision/outcome").textValue());
            for (String field : List.of("promotionStatus", "publicEvidenceStatus",
                    "externalNoveltyStatus", "formalProofStatus")) {
                assertEquals("NOT_EVALUATED", lane.required("lifecycle").required(field).textValue());
            }
            assertEquals(10, lane.at("/ledger/entries").size());
            int row = 0;
            for (var line : lane.at("/ledger/entries")) {
                var first = lanes.get(0).at("/ledger/entries").get(row++);
                for (String key : List.of("stage", "resource", "configured", "executed", "skipped", "remaining")) {
                    assertEquals(first.required(key), line.required(key), key);
                }
                assertEquals(line.required("configured").longValue(), line.required("executed").longValue()
                    + line.required("skipped").longValue() + line.required("remaining").longValue());
            }
        }
    }

    @Test
    void rejectsDifferentGenerationOrBranchInputsWithoutDroppingPartialLanes() throws Exception {
        var json = new JsonMapper();
        ArrayNode lanes = json.createArrayNode();
        var reference = (ObjectNode) json.readTree("""
            {"status":"COMPLETED","generation":{"briefHash":"brief"},"branches":[
              {"decision":{"inputs":["a","b"]}},{"decision":{"inputs":["a"]}}]}
            """);
        lanes.add(reference);
        var partial = lanes.addObject().put("status", "FAILED");
        partial.putObject("failure").put("stage", "GENERATION");
        ObjectNode differentGeneration = reference.deepCopy();
        ((ObjectNode) differentGeneration.required("generation")).put("briefHash", "other");
        lanes.add(differentGeneration);
        ObjectNode differentInputs = reference.deepCopy();
        ((ArrayNode) differentInputs.at("/branches/1/decision/inputs")).add("another");
        lanes.add(differentInputs);

        CampaignControllerComparison.verifyInputParity(lanes);

        assertEquals("COMPLETED", reference.required("status").textValue());
        assertEquals("GENERATION", partial.at("/failure/stage").textValue());
        for (var affected : List.of(differentGeneration, differentInputs)) {
            assertEquals("FAILED", affected.required("status").textValue());
            assertEquals("INPUT_PARITY", affected.at("/failure/stage").textValue());
            assertTrue(affected.has("branches"));
        }
    }

    private static long candidateCapacity(JsonNode decision) {
        for (var item : decision.required("plannedResources")) {
            if ("CANDIDATES".equals(item.required("resource").textValue())) {
                return item.required("amount").longValue();
            }
        }
        throw new AssertionError("missing candidate budget");
    }
}
