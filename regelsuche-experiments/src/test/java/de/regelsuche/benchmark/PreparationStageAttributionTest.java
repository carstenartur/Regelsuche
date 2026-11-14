package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.benchmark.PreparationGuardPanelTest.Input;
import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch.OperationLedger;
import de.regelsuche.search.reachability.PreparationProfile;
import de.regelsuche.search.reachability.UnifiedRulePreparationCoordinator;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** Post-freeze attribution on the two public panels, with observed work from an instrumented replay. */
class PreparationStageAttributionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void attributesBoundedWorkAndApplicationsAfterFreezingBothPublicPanels() throws Exception {
        String revision = revision();
        var experiment = new SymPyRuleAmplificationExperiment();
        var principals = experiment.principals().stream().map(RewriteApplicabilitySchema::fromPatternRule).toList();
        var coordinator = new UnifiedRulePreparationCoordinator(principals, experiment.cancellationRules(),
            revision, SymPyRuleAmplificationExperiment.BUDGET);
        var report = JSON.createObjectNode().put("schema", "regelsuche.sympy-preparation-attribution/v1")
            .put("repositoryRevision", revision).put("measurementProtocolCommit", "3ded89adb70c9241760b64356a71daa33c1a64fa")
            .put("principalInventory", coordinator.principalInventoryFingerprint())
            .put("preparationInventory", coordinator.preparationInventoryFingerprint())
            .put("exactRegistry", coordinator.exactRegistryFingerprint());
        report.set("budget", JSON.valueToTree(SymPyRuleAmplificationExperiment.BUDGET));
        var main = SymPyRuleAmplificationExperiment.cases().stream()
            .map(c -> new Input(c.id(), c.sourceExpression(), c.sourceAssumptions())).toList();
        assess(report.putObject("mainPanel"), coordinator, main, List.of(3, 4, 4, 4), List.of(0, 0, 0, 4),
            PreparationStageAttributionTest::qualifyOriginal);
        assess(report.putObject("guardPanel"), coordinator, guards(), List.of(1, 1, 1, 3), List.of(0, 0, 0, 2),
            (row, verdicts) -> PreparationGuardPanelTest.assertExpectedAuthorization(row.input().id(), row.profile(), verdicts));
        Path directory = Path.of(Boolean.getBoolean("regelsuche.maven.reactor") ? "target" : "build",
            "reports", "preparation-ablation");
        Files.createDirectories(directory);
        Files.write(directory.resolve("stage-attribution.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }

    private static String revision() throws Exception {
        String configured = System.getenv("REGELSUCHE_PREPARATION_REVISION");
        if (configured != null) return configured;
        var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        String current = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, git.waitFor());
        return current;
    }

    private static List<Input> guards() throws Exception {
        try (var stream = PreparationGuardPanelTest.class.getResourceAsStream("sympy-preparation-guards-v1.json")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            assertEquals("48a54303bea8416a122dc0400f833114700084e2ed4a6746c4fb36f8bb2209d5",
                PreparationAblation.digest(bytes));
            return JSON.readValue(bytes, PreparationGuardPanelTest.Corpus.class).cases();
        }
    }

    private static void assess(ObjectNode panel, UnifiedRulePreparationCoordinator coordinator, List<Input> inputs,
            List<Integer> expectedAuthorized, List<Integer> expectedConditional, BiConsumer<Observation, JsonNode> qualifyPanel) throws Exception {
        var frozen = new ArrayList<Observation>();
        for (var profile : PreparationProfile.values()) {
            for (var input : inputs) frozen.add(observe(profile, coordinator, input));
        }
        var authorized = new ArrayList<Integer>();
        var conditional = new ArrayList<Integer>();
        for (var profile : PreparationProfile.values()) {
            var lane = panel.putObject(profile.name()).put("identity", coordinator.profileHash(profile));
            var totals = lane.putObject("totals");
            var principals = lane.putObject("byPrincipal");
            var rows = lane.putArray("sources");
            for (var row : frozen) {
                if (row.profile() == profile) {
                    ObjectNode result = qualify(row, totals, principals);
                    qualifyPanel.accept(row, result.required("decisions"));
                    rows.add(result);
                }
            }
            authorized.add(totals.path("AUTHORIZED").asInt());
            conditional.add(totals.path("CONDITIONAL").asInt());
        }
        assertEquals(expectedAuthorized, authorized);
        assertEquals(expectedConditional, conditional);
    }

    private record Observation(PreparationProfile profile, Input input, List<JsonNode> decisions,
                               String digest, Map<String, OperationLedger> ledgers) {}

    private static Observation observe(PreparationProfile profile, UnifiedRulePreparationCoordinator coordinator,
            Input input) throws Exception {
        var assumptions = AssumptionSignature.ofExpressions(input.assumptions());
        var ordinary = coordinator.analyzeProfile(profile, input.source(), assumptions);
        var decisions = ordinary.stream().<JsonNode>map(r -> PreparationAblation.describe(r, input.source())).toList();
        String digest = PreparationAblation.digest(JSON.writeValueAsBytes(decisions));
        Map<String, OperationLedger> ledgers = new HashMap<>();
        var measured = coordinator.analyzeProfile(profile, input.source(), assumptions,
            id -> ledgers.computeIfAbsent(id, ignored -> new OperationLedger()));
        assertEquals(ordinary, measured, input.id());
        return new Observation(profile, input, decisions, digest, ledgers);
    }

    private static final Map<String, String> CONSEQUENCES = Map.of(
        SymPyRuleAmplificationExperiment.PYTHAGOREAN_RULE_ID, "1",
        SymPyRuleAmplificationExperiment.DIFFERENCE_OF_SQUARES_RULE_ID, "(x - y) * (x + y)",
        SymPyRuleAmplificationExperiment.TELESCOPING_RULE_ID, "1 / n - 1 / (n + 1)");

    private static void qualifyOriginal(Observation row, JsonNode verdicts) {
        var reference = SymPyRuleAmplificationExperiment.cases().stream()
            .filter(c -> c.id().equals(row.input().id())).findFirst().orElseThrow();
        for (JsonNode verdict : verdicts) {
            if (!verdict.has("consequence")) continue;
            assertEquals(reference.principalRuleId(), verdict.required("ruleId").asText());
            assertNotEquals("NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE", reference.expectedStatus().name());
            assertEquals(JSON.valueToTree(reference.requiredResultAssumptions()), verdict.required("retainedAssumptions"));
        }
    }

    private static ObjectNode qualify(Observation row, ObjectNode totals, ObjectNode principals) {
        var result = JSON.createObjectNode().put("id", row.input().id()).put("outcomesSha256", row.digest());
        result.put("source", row.input().source());
        result.set("sourceAssumptions", JSON.valueToTree(row.input().assumptions()));
        var decisions = result.putArray("decisions");
        var assumptions = AssumptionSignature.ofExpressions(row.input().assumptions()).normalizedAssumptions();
        for (var decision : row.decisions()) {
            String principal = decision.required("ruleId").asText();
            var verdict = PreparationGuardPanelTest.qualify(decision, assumptions);
            var measured = row.ledgers().get(principal);
            String status = verdict.required("status").asText();
            assertNotEquals("INCOMPATIBLE", status, row.input().id());
            verdict.put("decision", decision.required("status").asText());
            verdict.put("selectedStage", decision.required("selectedStage").asText());
            if (measured != null && measured.sourceAstNodes() > 0) verdict.set("localBridge", operations(measured));
            verdict.set("stages", decision.required("stages"));
            if (decision.has("candidate")) {
                var candidate = decision.required("candidate");
                assertEquals(CONSEQUENCES.get(principal), candidate.required("expression").asText(), row.input().id());
                verdict.set("consequence", candidate.required("expression"));
                verdict.set("retainedAssumptions", candidate.required("assumptions"));
                verdict.set("certificateHash", decision.required("certificateHash"));
            }
            record(totals, decision, verdict);
            record(principals.withObjectProperty(principal), decision, verdict);
            decisions.add(verdict);
        }
        return result;
    }

    private static ObjectNode operations(OperationLedger ledger) {
        var result = JSON.createObjectNode().put("certificateChecks", ledger.certificateChecks())
            .put("certificateHashesChecked", ledger.certificateHashesChecked())
            .put("rawSourceAstNodes", ledger.sourceAstNodes()).put("sizedCandidateAsts", ledger.sizedCandidateAsts())
            .put("peakObservedRawAstNodes", ledger.peakObservedAstNodes())
            .put("peakRawAstGrowthAboveSource", ledger.peakObservedAstNodes() - ledger.sourceAstNodes());
        result.set("formation", JSON.valueToTree(ledger.formation()));
        result.set("verification", JSON.valueToTree(ledger.certificateVerification()));
        result.set("coordinatorValidation", JSON.valueToTree(ledger.coordinatorValidation()));
        return result;
    }

    private static void record(ObjectNode tally, JsonNode decision, JsonNode verdict) {
        add(tally, verdict.required("status").asText(), 1);
        add(tally.withObjectProperty("decisions"), decision.required("status").asText(), 1);
        var stages = tally.withObjectProperty("workByStage");
        for (var stage : decision.required("stages")) {
            var work = stages.withObjectProperty(stage.required("name").asText());
            add(work, "attempts", 1);
            sumFields(work, stage);
            if (stage.path("bridgeWork").isObject()) sumFields(work, stage.required("bridgeWork"));
        }
        if (verdict.has("localBridge")) recordBridge(stages.withObjectProperty("LOCAL_BRIDGE"), verdict.required("localBridge"));
        var paths = tally.withObjectProperty("pathsByAuthorization");
        paths.withObjectProperty("AUTHORIZED");
        paths.withObjectProperty("CONDITIONAL");
        if (decision.has("candidate")) {
            recordCandidate(paths.withObjectProperty(verdict.required("status").asText()), decision);
        }
    }

    private static void recordBridge(ObjectNode stage, JsonNode bridge) {
        add(stage, "certificateChecks", bridge.required("certificateChecks").asLong());
        add(stage, "certificateHashesChecked", bridge.required("certificateHashesChecked").asLong());
        add(stage, "sizedCandidateAsts", bridge.required("sizedCandidateAsts").asLong());
        stage.put("peakRawAstGrowthAboveSource", Math.max(stage.path("peakRawAstGrowthAboveSource").asLong(),
            bridge.required("peakRawAstGrowthAboveSource").asLong()));
        sumFields(stage.withObjectProperty("formation"), bridge.required("formation"));
        sumFields(stage.withObjectProperty("verification"), bridge.required("verification"));
        sumFields(stage.withObjectProperty("coordinatorValidation"), bridge.required("coordinatorValidation"));
    }

    private static void recordCandidate(ObjectNode paths, JsonNode decision) {
        var candidate = decision.required("candidate");
        add(paths.withObjectProperty("applicationsBySelectedStage"), decision.required("selectedStage").asText(), 1);
        add(paths, "preparationSteps", candidate.required("preparationDepth").asLong());
        add(paths, "canonicalEndpointAstNodeDeltaSum", candidate.required("canonicalAstNodeDelta").asLong());
        sumFields(paths, candidate.required("executionWork"));
        var preparers = paths.withObjectProperty("preparerUses");
        var lineage = candidate.required("primitiveRuleIds");
        for (int i = 0; i < lineage.size() - 1; i++) add(preparers, lineage.get(i).asText(), 1);
    }

    private static void sumFields(ObjectNode totals, JsonNode fields) {
        fields.properties().forEach(entry -> {
            JsonNode value = entry.getValue();
            if (!value.isIntegralNumber()) return;
            String key = entry.getKey();
            if (key.equals("maxFrontierSize")) totals.put(key, Math.max(totals.path(key).asLong(), value.asLong()));
            else add(totals, key, value.asLong());
        });
    }

    private static void add(ObjectNode totals, String key, long value) {
        totals.put(key, totals.path(key).asLong() + value);
    }
}
