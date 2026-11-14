package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.docs.HiddenRulePilotCampaign.PilotCase;
import de.regelsuche.docs.HiddenRulePilotCampaign.PilotReport;
import de.regelsuche.docs.HiddenRulePilotRunner.RuntimeTask;
import de.regelsuche.search.strategy.AStarSearchStrategy;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.TransformationEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Comparative Track B replays the twenty opaque tasks and the evaluation-only #227 manifest. */
class HiddenRuleComparisonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void comparesBothSchedulersOnTheCompleteOpaqueCorpus() throws Exception {
        List<PilotCase> cases = HiddenRulePilotCatalog.cases();
        PilotReport bestFirst = HiddenRulePilotTestEvidence.report();
        var astarCampaign = new HiddenRulePilotCampaign(new HiddenRulePilotRunner(new AStarSearchStrategy()));
        PilotReport astar = astarCampaign.run(cases);

        List<String> caseIds = cases.stream().map(c -> c.task().opaqueCaseId()).toList();
        assertEquals(20, caseIds.stream().distinct().count());
        ObjectNode comparison = JSON.createObjectNode();
        comparison.put("schema", "regelsuche.hidden-rule-comparison/v1");
        comparison.put("track", "HIDDEN_RULE_REDISCOVERY");
        ObjectNode visibility = comparison.putObject("informationParity");
        visibility.put("concreteTaskEndpointsVisible", true);
        visibility.put("primitiveInventoryVisible", true);
        visibility.put("generalizedReferenceVisibleToRunner", false);
        visibility.put("familyVisibleToRunner", false);
        visibility.put("holdoutsUsedDuringFormation", false);
        comparison.set("tasks", taskManifest(cases));
        ObjectNode profiles = comparison.putObject("profiles");
        profiles.set("best-first", checkedResults(bestFirst, caseIds));
        profiles.set("project-a-star", checkedResults(astar, caseIds));

        Path directory = Path.of(System.getProperty("regelsuche.hiddenRulePilot.outputDirectory",
            "build/reports/hidden-rule-pilot"));
        HiddenRulePilotCampaignTest.checkMavenOutput(directory.resolve("comparison.json"));
        Files.createDirectories(directory);
        Files.write(directory.resolve("comparison.json"), JSON.writeValueAsBytes(comparison));
    }

    private static JsonNode checkedResults(PilotReport report, List<String> caseIds) throws Exception {
        assertEquals(caseIds, report.cases().stream().map(c -> c.opaqueCaseId()).toList());
        assertTrue(report.cases().stream().allMatch(c -> c.split().passed()));
        assertTrue(report.cases().stream().allMatch(c -> c.evaluation().leakageViolations().isEmpty()));
        assertEquals(report.negativeHoldouts(), report.evaluatedNegativeHoldouts() + report.skippedNegativeHoldouts());
        // A learning, compilation or holdout failure remains a measured result.
        return JSON.readTree(report.toJson());
    }

    private static ArrayNode taskManifest(List<PilotCase> cases) {
        ArrayNode manifest = JSON.createArrayNode();
        for (PilotCase pilotCase : cases) {
            RuntimeTask task = pilotCase.task();
            ObjectNode entry = manifest.addObject();
            entry.put("id", task.opaqueCaseId());
            entry.put("input", task.inputExpression());
            entry.put("observableEndpoint", task.target().targetExpression());
            entry.set("primitiveInventory", inventory(task.primitiveEngine()));
            entry.set("searchLimits", JSON.valueToTree(task.heuristic()));
            entry.set("positiveHoldouts", JSON.valueToTree(task.positiveHoldouts()));
            entry.set("negativeHoldouts", JSON.valueToTree(task.negativeHoldouts()));
        }
        return manifest;
    }

    private static ObjectNode inventory(TransformationEngine engine) {
        ObjectNode result = JSON.createObjectNode();
        result.put("type", engine.getClass().getName());
        if (engine instanceof AstRewriteTransformationEngine ast) {
            ArrayNode rules = result.putArray("rules");
            ast.rules().forEach(rule -> rules.add(rule.id()));
        } else if (engine instanceof HypothesisTransformationEngine hypotheses) {
            result.set("base", inventory(hypotheses.baseEngine()));
            result.put("maxHypothesisCandidates", hypotheses.maxHypothesisCandidates());
            ArrayNode operators = result.putArray("operators");
            hypotheses.operators().forEach(operator -> operators.add(operator.getClass().getName()));
        } else {
            throw new IllegalStateException("Uncharacterized primitive inventory: " + engine.getClass().getName());
        }
        return result;
    }
}
