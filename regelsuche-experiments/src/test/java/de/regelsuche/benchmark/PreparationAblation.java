package de.regelsuche.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.search.reachability.PatternTargetedLocalBridgeSearch;
import de.regelsuche.search.reachability.PreparationProfile;
import de.regelsuche.search.reachability.UnifiedRulePreparationCoordinator;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Source-only execution followed by classification against the existing public pilot. */
final class PreparationAblation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExpressionCanonicalizer SIZES = new ExpressionCanonicalizer();

    static ObjectNode measure(String revision) throws Exception {
        var experiment = new SymPyRuleAmplificationExperiment();
        var schemas = experiment.principals().stream().map(RewriteApplicabilitySchema::fromPatternRule).toList();
        var preparers = experiment.cancellationRules();
        var budget = SymPyRuleAmplificationExperiment.BUDGET;
        var coordinator = new UnifiedRulePreparationCoordinator(schemas, preparers, revision, budget);
        var verifier = new UnifiedRulePreparationCoordinator(schemas, preparers, revision, budget);

        var report = JSON.createObjectNode();
        report.put("schema", "regelsuche.preparation-stage-ablation/v1");
        report.put("repositoryRevision", revision);
        report.put("principalInventory", coordinator.principalInventoryFingerprint());
        report.put("preparationInventory", coordinator.preparationInventoryFingerprint());
        report.put("exactRegistry", coordinator.exactRegistryFingerprint());
        report.set("budget", JSON.valueToTree(budget));
        var profiles = report.putArray("profiles");
        for (var profile : PreparationProfile.values()) {
            var lane = profiles.addObject();
            lane.put("profile", profile.name());
            lane.put("identity", coordinator.profileHash(profile));
            var cases = lane.putArray("cases");
            for (var reference : SymPyRuleAmplificationExperiment.cases()) {
                String source = reference.sourceExpression();
                var assumptions = AssumptionSignature.ofExpressions(reference.sourceAssumptions());
                var results = coordinator.analyzeProfile(profile, source, assumptions);
                var frozen = JSON.createArrayNode();
                for (var result : results) frozen.add(describe(result, source));
                String hash = digest(JSON.writeValueAsBytes(frozen));
                var repeated = verifier.analyzeProfile(profile, source, assumptions);
                if (!results.equals(repeated)) throw new IllegalStateException("Source-only replay differs for " + reference.id());

                var row = cases.addObject();
                row.put("id", reference.id());
                row.put("source", source);
                row.set("sourceAssumptions", JSON.valueToTree(reference.sourceAssumptions()));
                row.put("outcomesSha256", hash);
                row.set("outcomes", frozen);
                // These labels are consulted only after source-only outcomes have been frozen and replayed.
                row.put("expectedPrincipal", reference.principalRuleId());
                row.put("nearMiss", reference.expectedStatus() == PatternTargetedLocalBridgeSearch.Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE);
                row.set("expectedResultAssumptions", JSON.valueToTree(reference.requiredResultAssumptions()));
                row.put("replayExecutions", 1);
                var replayLedger = JSON.createArrayNode();
                for (var result : repeated) replayLedger.add(describe(result, source));
                row.put("additionalReplayLedgerSha256", digest(JSON.writeValueAsBytes(replayLedger)));
            }
        }
        return report;
    }

    private static ObjectNode describe(PreparationProfile.Result result, String source) {
        var node = JSON.createObjectNode();
        node.put("ruleId", result.ruleId());
        node.put("ruleFingerprint", result.ruleFingerprint());
        node.put("status", result.status());
        node.put("selectedStage", result.selectedStage());
        node.put("detail", result.detail());
        node.put("certificateHash", result.certificateHash());
        var stages = node.putArray("stages");
        for (var stage : result.stages()) {
            ObjectNode item = JSON.valueToTree(stage);
            item.set("reachedLimits", JSON.valueToTree(stage.reachedLimits().stream().sorted().toList()));
            stages.add(item);
        }
        result.candidate().ifPresent(move -> {
            var candidate = node.putObject("candidate");
            candidate.put("expression", move.transformedExpression());
            candidate.set("assumptions", JSON.valueToTree(move.assumptions()));
            candidate.set("primitiveRuleIds", JSON.valueToTree(move.primitiveRuleIds()));
            candidate.put("preparationDepth", move.primitiveStepCount() - 1);
            candidate.set("executionWork", JSON.valueToTree(move.executionWork()));
            candidate.put("provenanceHash", move.provenance().contentHash());
            candidate.put("canonicalAstNodeDelta", SIZES.astNodeCount(move.transformedExpression()) - SIZES.astNodeCount(source));
        });
        return node;
    }

    private static String digest(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
