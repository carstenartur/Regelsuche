package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.search.reachability.PreparationProfile;
import de.regelsuche.search.reachability.UnifiedRulePreparationCoordinator;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class PreparationGuardPanelTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CORPUS_SHA = "48a54303bea8416a122dc0400f833114700084e2ed4a6746c4fb36f8bb2209d5";
    record Input(String id, String source, List<String> assumptions) {}
    record Corpus(String schema, List<Input> cases) {}

    @Test
    void allFamiliesKeepFalseAndUnprovedPremisesSeparateFromAuthorizedApplications() throws Exception {
        byte[] bytes;
        try (var in = getClass().getResourceAsStream("sympy-preparation-guards-v1.json")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        assertEquals(CORPUS_SHA, PreparationAblation.digest(bytes));
        var corpus = JSON.readValue(bytes, Corpus.class);
        assertEquals(9, corpus.cases().size());
        String revision = System.getenv("REGELSUCHE_PREPARATION_REVISION");
        if (revision == null) {
            var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
            revision = new String(git.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            assertEquals(0, git.waitFor());
        }
        var experiment = new SymPyRuleAmplificationExperiment();
        var schemas = experiment.principals().stream().map(RewriteApplicabilitySchema::fromPatternRule).toList();
        var preparers = experiment.cancellationRules();
        var budget = SymPyRuleAmplificationExperiment.BUDGET;
        var producer = new UnifiedRulePreparationCoordinator(schemas, preparers, revision, budget);
        var replay = new UnifiedRulePreparationCoordinator(schemas, preparers, revision, budget);
        var report = JSON.createObjectNode().put("schema", "regelsuche.sympy-preparation-guard-results/v1")
            .put("repositoryRevision", revision).put("sourceSha256", CORPUS_SHA)
            .put("principalInventory", producer.principalInventoryFingerprint())
            .put("preparationInventory", producer.preparationInventoryFingerprint());
        report.set("budget", JSON.valueToTree(budget));
        var profiles = report.putArray("profiles");
        int trials = 0;
        int incompatible = 0;
        for (var profile : PreparationProfile.values()) {
            var lane = profiles.addObject().put("profile", profile.name()).put("identity", producer.profileHash(profile));
            var cases = lane.putArray("cases");
            for (var input : corpus.cases()) {
                var assumptions = AssumptionSignature.ofExpressions(input.assumptions());
                var produced = producer.analyzeProfile(profile, input.source(), assumptions);
                assertEquals(3, produced.size());
                var frozen = JSON.createArrayNode();
                for (var result : produced) frozen.add(PreparationAblation.describe(result, input.source()));
                String frozenHash = PreparationAblation.digest(JSON.writeValueAsBytes(frozen));
                var repeated = replay.analyzeProfile(profile, input.source(), assumptions);
                assertEquals(produced, repeated);
                var row = cases.addObject().put("id", input.id()).put("source", input.source())
                    .put("outcomesSha256", frozenHash).put("replayExecutions", 1);
                row.set("sourceAssumptions", JSON.valueToTree(input.assumptions()));
                row.set("outcomes", frozen);
                var qualification = row.putArray("authorization");
                for (JsonNode raw : frozen) {
                    var verdict = qualify(raw, assumptions.normalizedAssumptions());
                    qualification.add(verdict);
                    if ("INCOMPATIBLE".equals(verdict.required("status").textValue())) incompatible++;
                    if ("AUTHORIZED".equals(verdict.required("status").textValue())) {
                        assertTrue(input.id().endsWith("1"), row.toString());
                    }
                }
                assertExpectedAuthorization(input.id(), profile, qualification);
                trials += produced.size();
            }
        }
        assertEquals(108, trials);
        assertEquals(0, incompatible, "a replayed candidate cannot require a divisor the source declares zero");
        var out = Path.of(Boolean.getBoolean("regelsuche.maven.reactor") ? "target" : "build",
            "reports", "preparation-ablation", "guards-v1.json");
        Files.createDirectories(out.getParent());
        Files.write(out, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(report));
    }

    static void assertExpectedAuthorization(String id, PreparationProfile profile, JsonNode verdicts) {
        Map<Character, String> principals = Map.of(
            'T', SymPyRuleAmplificationExperiment.PYTHAGOREAN_RULE_ID,
            'P', SymPyRuleAmplificationExperiment.DIFFERENCE_OF_SQUARES_RULE_ID,
            'R', SymPyRuleAmplificationExperiment.TELESCOPING_RULE_ID);
        String expected = "NO_CANDIDATE";
        if (id.equals("R1")) expected = "AUTHORIZED";
        if (profile == PreparationProfile.SAFE_PREPARATION_PLUS_LOCAL_BRIDGE) {
            if (Set.of("T1", "P1").contains(id)) expected = "AUTHORIZED";
            if (Set.of("T2", "P2").contains(id)) expected = "CONDITIONAL";
        }
        for (var row : verdicts) {
            boolean principal = row.required("ruleId").asText().equals(principals.get(id.charAt(0)));
            assertEquals(principal ? expected : "NO_CANDIDATE", row.required("status").asText(),
                () -> id + " under " + profile + ": " + row);
        }
    }

    static ObjectNode qualify(JsonNode raw, List<String> inputs) {
        var verdict = JSON.createObjectNode().put("ruleId", raw.required("ruleId").asText());
        if (!raw.has("candidate")) return verdict.put("status", "NO_CANDIDATE");
        var introduced = new TreeSet<String>();
        var incompatible = new TreeSet<String>();
        for (JsonNode condition : raw.at("/candidate/assumptions")) {
            String premise = condition.textValue();
            if (!inputs.contains(premise)) {
                introduced.add(premise);
                // All preparation edges in this pinned scalar panel introduce only nonzero requirements.
                assertTrue(premise.endsWith(" != 0"), premise);
                String lhs = premise.substring(0, premise.length() - " != 0".length());
                if (inputs.contains(lhs + " = 0")) incompatible.add(premise);
            }
        }
        verdict.set("additionalObligations", JSON.valueToTree(introduced));
        verdict.set("contradictedObligations", JSON.valueToTree(incompatible));
        if (!incompatible.isEmpty()) return verdict.put("status", "INCOMPATIBLE");
        return verdict.put("status", introduced.isEmpty() ? "AUTHORIZED" : "CONDITIONAL");
    }
}
