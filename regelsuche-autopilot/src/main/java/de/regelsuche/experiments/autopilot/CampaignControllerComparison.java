package de.regelsuche.experiments.autopilot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.experiments.autopilot.AutonomousProductionMiningRunner.MiningBatch;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Executes each preregistered controller against a fresh instance of the public campaign. */
final class CampaignControllerComparison {
    private static final JsonMapper JSON = new JsonMapper();
    private static final String SCHEMA = "regelsuche.production-controller-comparison/v1";
    private static final String PROTOCOL_SHA256 =
        "7ddc243adfd1297f1c1c331453872cbea501fe2beddcfd7145a7c1e8be250f8e";

    private enum Policy { PRODUCTION_FEEDBACK, FIXED_UNIFORM, NO_FEEDBACK }

    static void write(Path destination) {
        var brief = PinnedAutonomousProductionCampaign.brief();
        ObjectNode report = JSON.createObjectNode();
        report.put("schema", SCHEMA);
        report.put("protocolSha256", PROTOCOL_SHA256);
        report.set("brief", artifact(brief.toCanonicalJson()));
        report.putArray("initialEvidence");
        report.put("costModel", AutonomousCampaignResourceLedger.SCHEMA);
        report.putArray("coverageGaps")
            .add("NO_CHOICE_BETWEEN_ELIGIBLE_CONTINUATIONS")
            .add("NO_COMMON_CURRENCY_FOR_UNIFORM_CROSS_STAGE_ALLOCATION")
            .add("SINGLE_PUBLIC_CAMPAIGN");
        report.put("allocationScope", "CANDIDATE_SLOTS_BETWEEN_TWO_PREDECLARED_FORMATION_BATCHES");
        ArrayNode lanes = report.putArray("lanes");
        for (Policy policy : Policy.values()) {
            execute(brief, policy, lanes.addObject());
        }
        verifyInputParity(lanes);
        Path absolute = destination.toAbsolutePath();
        try {
            Files.createDirectories(absolute.getParent());
            Path pending = Files.createTempFile(absolute.getParent(), "campaign-controllers-", ".tmp");
            try {
                Files.write(pending, JSON.writeValueAsBytes(report));
                Files.move(pending, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not write controller comparison", exception);
        }
        for (var lane : lanes) {
            if (!"COMPLETED".equals(lane.required("status").asText())) {
                throw new IllegalStateException("Incomplete controller comparison; see " + destination);
            }
        }
    }

    static void verifyInputParity(ArrayNode lanes) {
        JsonNode generation = null;
        JsonNode fullBatch = null;
        JsonNode pair = null;
        for (JsonNode lane : lanes) {
            if (!"COMPLETED".equals(lane.required("status").asText())) {
                continue;
            }
            JsonNode actual = lane.required("generation");
            JsonNode fullInputs = lane.at("/branches/0/decision/inputs");
            JsonNode pairInputs = lane.at("/branches/1/decision/inputs");
            if (generation == null) {
                generation = actual;
                fullBatch = fullInputs;
                pair = pairInputs;
            } else if (!actual.equals(generation) || !fullInputs.equals(fullBatch) || !pairInputs.equals(pair)) {
                recordFailure((ObjectNode) lane, "INPUT_PARITY",
                    new IllegalStateException("Generation or formation inputs differ across completed lanes"));
            }
        }
    }

    private static void execute(AutonomousResearchBriefV2 brief, Policy policy, ObjectNode lane) {
        lane.put("policy", policy.name());
        long slots = brief.budget(EvidenceStage.CANDIDATE_FORMATION).configured(ResourceKind.CANDIDATES);
        long pairSlots = policy == Policy.FIXED_UNIFORM ? slots / 2 : 1;
        lane.put("configurationHash", AutonomousResearchBriefV2.hash(String.join("\n",
            SCHEMA, PROTOCOL_SHA256, brief.contentHash(), policy.name(), Long.toString(pairSlots))));
        lane.put("decisionSource", policy == Policy.PRODUCTION_FEEDBACK
            ? "PRODUCTION_RECEIPTS" : "STATIC_HORIZON_BEFORE_EXECUTION");
        if (policy != Policy.PRODUCTION_FEEDBACK) {
            var terminal = AutonomousCampaignArtifactsV2.plan(brief, List.of());
            lane.set("nextPlan", artifact(terminal.toCanonicalJson()));
        }
        String stage = "GENERATION";
        try {
            var generation = new AutonomousProductionGenerationRunner().run(
                brief, PinnedAutonomousProductionCampaign.seeds(), 1);
            lane.set("generation", artifact(generation.toCanonicalJson()));
            lane.set("seedCatalog", artifact(generation.seedCatalog().toCanonicalJson()));
            lane.set("generationReceipt", artifact(generation.receipt().toCanonicalJson()));
            stage = "CANDIDATE_FORMATION";
            var miner = new AutonomousProductionMiningRunner();
            var mining = policy == Policy.PRODUCTION_FEEDBACK
                ? miner.run(generation) : miner.run(generation, pairSlots);
            lane.set("plan", artifact(mining.plan().toCanonicalJson()));
            lane.set("evidenceDag", artifact(mining.dag().toCanonicalJson()));
            lane.set("formationReceipt", artifact(mining.formationReceipt().toCanonicalJson()));
            var branches = lane.putArray("branches");
            var candidates = lane.putArray("candidateIds");
            for (MiningBatch batch : List.of(mining.fullBatch(), mining.rejectionBatch())) {
                var branch = branches.addObject();
                branch.set("decision", artifact(batch.decision().toCanonicalJson()));
                branch.set("receipt", artifact(batch.binding().receipt().toCanonicalJson()));
                branch.set("execution", artifact(batch.execution().toCanonicalJson()));
                batch.binding().receipt().outputs().forEach(item -> candidates.add(item.conjectureId()));
            }
            stage = "LIFECYCLE";
            var lifecycle = new AutonomousProductionLifecycleRunner().run(mining);
            lane.set("lifecycle", artifact(lifecycle.toCanonicalJson()));
            lane.set("downstreamLedger", artifact(lifecycle.stageLedger().toCanonicalJson()));
            lane.set("lifecycleDecision", artifact(lifecycle.lifecycleDecision().toCanonicalJson()));
            lane.set("validation", artifact(lifecycle.validationJson()));
            lane.set("counterexample", artifact(lifecycle.counterexampleJson()));
            lane.set("novelty", artifact(lifecycle.noveltyJson()));
            lane.set("proof", artifact(lifecycle.proof().toCanonicalJson()));
            lane.set("solverObligation", artifact(lifecycle.proof().obligation().toCanonicalJson()));
            lane.set("solverResult", artifact(lifecycle.proof().result().toCanonicalJson()));
            stage = "COMPLETION";
            if (policy == Policy.PRODUCTION_FEEDBACK) {
                var campaign = new AutonomousProductionCampaignRunner().run(lifecycle);
                lane.set("nextPlan", artifact(campaign.nextPlan().toCanonicalJson()));
                lane.set("feedback", artifact(campaign.feedback().toCanonicalJson()));
                lane.set("ledger", artifact(campaign.resourceLedger().toCanonicalJson()));
                lane.put("productionCampaignHash", campaign.contentHash());
                lane.put("stopReason", campaign.feedback().disposition());
            } else {
                lane.set("ledger", artifact(AutonomousCampaignResourceLedger.create(lifecycle).toCanonicalJson()));
                lane.put("stopReason", "FIXED_ONE_ROUND_HORIZON");
            }
            lane.put("status", "COMPLETED");
        } catch (RuntimeException failure) {
            recordFailure(lane, stage, failure);
        }
    }

    private static void recordFailure(ObjectNode lane, String stage, RuntimeException failure) {
        lane.put("status", "FAILED");
        var diagnostic = lane.putObject("failure");
        diagnostic.put("stage", stage);
        diagnostic.put("type", failure.getClass().getName());
        diagnostic.put("message", failure.getMessage());
    }

    private static JsonNode artifact(String canonicalJson) {
        try {
            return JSON.readTree(canonicalJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Campaign returned an invalid JSON artifact", exception);
        }
    }

    private CampaignControllerComparison() { }
}
