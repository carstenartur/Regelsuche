package de.regelsuche.experiments.autopilot;

import static de.regelsuche.experiments.autopilot.ProductionCandidateContinuation.parse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateDecision;
import de.regelsuche.experiments.autopilot.AutonomousProductionGenerationRunner.GeneratedObservation;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage.*;

/** Paired public campaign experiment with a preregistered budget of stage dispatch opportunities. */
public final class StageAllocationComparison {
    static final String PLAN_HASH = "sha256:0b67a54b17f48304e38226579ff0f013180d4400fc679185a360ff023c981eb9";
    private static final int TICKETS = 14;
    private static final List<EvidenceStage> DOWNSTREAM =
        List.of(VALIDATION, PROJECT_NOVELTY, PROOF, LIFECYCLE_HANDOFF);
    private static final JsonMapper JSON = new JsonMapper();

    enum Arm { UNIFORM_ALL_STAGES, STATIC_NO_FEEDBACK, NATIVE_RECEIPT_DRIVEN }

    private StageAllocationComparison() {}

    private static ObjectNode object() { return JSON.createObjectNode(); }

    public static void write(Path planFile, Path output) {
        try {
            String planText = Files.readString(planFile);
            if (!AutonomousResearchBriefV2.hash(planText).equals(PLAN_HASH)) {
                throw new IllegalArgumentException("expected the frozen stage-allocation protocol");
            }
            JsonNode plan = parse(planText);
            ObjectNode report = object().put("schema", "regelsuche.track-f-stage-allocation/v1")
                .put("planSha256", PLAN_HASH);
            var reference = new AutonomousProductionCampaignRunner().runPinned(1);
            report.set("originalCampaign", parse(reference.toCanonicalJson()));
            report.set("originalTerminalFeedback", parse(reference.feedback().toCanonicalJson()));
            ArrayNode lanes = report.putArray("lanes");
            for (JsonNode panel : plan.required("panels")) {
                for (Arm arm : Arm.values()) {
                    lanes.add(run(panel, arm));
                }
            }
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ObjectNode run(JsonNode panel, Arm arm) {
        var generation = new AutonomousProductionGenerationRunner().runPinned(1);
        var brief = generation.brief();
        Accounting ledger = new Accounting();
        String panelId = panel.required("id").asText();
        ObjectNode row = object().put("panel", panelId).put("arm", arm.name());
        row.set("brief", parse(brief.toCanonicalJson()));
        row.set("generation", parse(generation.toCanonicalJson()));
        row.set("seedCatalog", parse(generation.seedCatalog().toCanonicalJson()));
        row.set("generationReceipt", parse(generation.receipt().toCanonicalJson()));
        ledger.execute(GENERATION, -1, "", generation.receipt().contentHash());
        ledger.charge(generation.receipt().executedResources(), generation.receipt().skippedResources());
        if (arm == Arm.UNIFORM_ALL_STAGES) {
            ledger.skip(GENERATION, -1, "SINGLE_REGISTERED_CATALOG");
        }

        List<AggregateDecision> decisions = new ArrayList<>();
        List<List<GeneratedObservation>> inputs = new ArrayList<>();
        for (JsonNode selector : panel.required("batches")) {
            List<GeneratedObservation> selected = select(generation.observations(), selector);
            decisions.add(AutonomousEvidenceDagV2.planCandidateFormation(brief,
                panelId + "-batch-" + inputs.size(), selected.stream().map(GeneratedObservation::branch).toList(),
                Map.of(ResourceKind.MINING_BATCHES, 1L, ResourceKind.CANDIDATES, 6L),
                "registered public panel " + panelId + ", batch " + inputs.size()));
            inputs.add(selected);
        }
        var formationPlan = AutonomousCampaignArtifactsV2.plan(brief, decisions);
        row.set("formationPlan", parse(formationPlan.toCanonicalJson()));
        ArrayNode batches = row.putArray("batches");
        List<ProductionCandidateContinuation> candidates = new ArrayList<>();
        for (int index = 0; index < decisions.size(); index++) {
            var batch = AutonomousProductionMiningRunner.mineBatch(brief, formationPlan, decisions.get(index), inputs.get(index));
            var receipt = batch.binding().receipt();
            ObjectNode artifact = batches.addObject();
            artifact.set("decision", parse(batch.decision().toCanonicalJson()));
            artifact.set("receipt", parse(receipt.toCanonicalJson()));
            artifact.set("mining", parse(batch.evidence().toJson()));
            artifact.set("lineage", parse(batch.lineage().toCanonicalJson()));
            artifact.set("inputSeeds", JSON.valueToTree(inputs.get(index).stream().map(item -> item.seed().id()).toList()));
            ledger.execute(CANDIDATE_FORMATION, index, "", receipt.contentHash());
            ledger.charge(receipt.executedResources(), receipt.skippedResources());
            candidates.addAll(ProductionCandidateContinuation.fromBatch(index, batch, brief.deterministicSeed()));
        }

        switch (arm) {
            case NATIVE_RECEIPT_DRIVEN -> AutonomousCampaignFeedback.continueEligible(
                candidates, ledger.remaining(), ledger);
            case STATIC_NO_FEEDBACK -> {
                List<ProductionCandidateContinuation> first = candidates.stream().filter(c -> c.batch() == 0).toList();
                for (int ordinal = 0; ordinal < 2; ordinal++) {
                    var candidate = ordinal < first.size() ? first.get(ordinal) : null;
                    for (EvidenceStage stage : DOWNSTREAM) {
                        dispatch(stage, candidate, 0, ledger);
                    }
                }
                dispatch(PROOF, first.stream().filter(c -> c.nextStage() == PROOF).findFirst().orElse(null), 0, ledger);
            }
            case UNIFORM_ALL_STAGES -> {
                for (EvidenceStage stage : DOWNSTREAM) {
                    var eligible = candidates.stream().filter(c -> c.nextStage() == stage).limit(2).toList();
                    for (int slot = 0; slot < 2; slot++) {
                        dispatch(stage, slot < eligible.size() ? eligible.get(slot) : null, -1, ledger);
                    }
                }
            }
        }
        row.set("candidates", JSON.valueToTree(candidates.stream().map(ProductionCandidateContinuation::evidence).toList()));
        row.set("dispatch", ledger.events);
        row.put("configuredTickets", TICKETS).put("remainingTickets", ledger.remaining());
        row.set("resourceLedger", ledger.resources(brief));
        return row;
    }

    private static List<GeneratedObservation> select(List<GeneratedObservation> all, JsonNode selector) {
        if ("ALL".equals(selector.asText())) { return all; }
        Set<String> ids = new HashSet<>();
        selector.forEach(item -> ids.add(item.asText()));
        List<GeneratedObservation> selected = all.stream().filter(item -> ids.contains(item.seed().id())).toList();
        if (selected.size() != selector.size()) { throw new IllegalArgumentException("missing or duplicate seed"); }
        return selected;
    }

    private static void dispatch(EvidenceStage stage, ProductionCandidateContinuation candidate,
            int reservedBatch, Accounting ledger) {
        if (candidate != null && candidate.nextStage() == stage) {
            candidate.advance(ledger);
            return;
        }
        ledger.skip(stage, reservedBatch, "NO_ELIGIBLE_RESERVED_LINEAGE");
        if (stage == VALIDATION) {
            ledger.skip(COUNTEREXAMPLE_SEARCH, reservedBatch, "NO_ELIGIBLE_RESERVED_LINEAGE");
        }
    }

    static final class Accounting implements ProductionCandidateContinuation.DispatchSink {
        private final ArrayNode events = JSON.createArrayNode();
        private final Map<ResourceKind, Long> executed = new EnumMap<>(ResourceKind.class);
        private final Map<ResourceKind, Long> skipped = new EnumMap<>(ResourceKind.class);

        int remaining() { return TICKETS - events.size(); }

        @Override
        public void executed(EvidenceStage stage, int batch, String id, String hash, ResourceKind kind, long work) {
            execute(stage, batch, id, hash);
            charge(kind, work, 0);
        }

        void execute(EvidenceStage stage, int batch, String id, String receiptHash) {
            event(stage, batch, "EXECUTED").put("lineage", id).put("receiptHash", receiptHash);
        }

        void skip(EvidenceStage stage, int batch, String reason) {
            event(stage, batch, "SKIPPED").put("reason", reason);
        }

        private ObjectNode event(EvidenceStage stage, int batch, String status) {
            if (remaining() <= 0) { throw new IllegalStateException("dispatch budget exhausted"); }
            return events.addObject().put("stage", stage.name()).put("batch", batch).put("status", status);
        }

        void charge(ResourceKind kind, long work, long skippedWork) {
            executed.merge(kind, work, Math::addExact);
            skipped.merge(kind, skippedWork, Math::addExact);
        }

        void charge(Map<ResourceKind, Long> work, Map<ResourceKind, Long> skippedWork) {
            work.forEach((kind, amount) -> charge(kind, amount, 0));
            skippedWork.forEach((kind, amount) -> charge(kind, 0, amount));
        }

        ObjectNode resources(AutonomousResearchBriefV2 brief) {
            ObjectNode result = object();
            for (EvidenceStage stage : EvidenceStage.values()) {
                ObjectNode stageRow = result.putObject(stage.name());
                brief.budget(stage).resources().forEach((kind, limit) -> {
                    long work = executed.getOrDefault(kind, 0L);
                    long unused = skipped.getOrDefault(kind, 0L);
                    long left = limit - work - unused;
                    if (left < 0) { throw new IllegalStateException("native resource ceiling exceeded " + kind); }
                    stageRow.putObject(kind.name()).put("configured", limit).put("executed", work)
                        .put("skipped", unused).put("remaining", left);
                });
            }
            return result;
        }
    }
}
