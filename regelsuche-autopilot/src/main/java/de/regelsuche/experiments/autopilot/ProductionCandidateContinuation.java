package de.regelsuche.experiments.autopilot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateOutput;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.mining.KnownRuleRepository;
import de.regelsuche.mining.OpenTargetConjectureEvaluator;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationPlan;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationStatus;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.mining.OpenTargetHypothesisCandidateAdapter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Advances one mined output through the same gates and holdouts as the pinned campaign. */
public final class ProductionCandidateContinuation {
    private static final JsonMapper JSON = new JsonMapper();

    @FunctionalInterface
    public interface DispatchSink {
        void executed(EvidenceStage stage, int batch, String branchId, String receiptHash,
            ResourceKind resource, long work);
    }

    private final int batch;
    private final CandidateOutput output;
    private final OpenTargetConjecture conjecture;
    private final EvaluationPlan plan;
    private final ObjectNode receipts = JSON.createObjectNode();
    private EvidenceStage next = EvidenceStage.VALIDATION;
    private EvaluationReport evaluation;
    private NoveltyReport novelty;
    private ProofReport proof;
    private String status = "FORMED";

    /** Create the continuations strictly from the native formation receipt and replayed miner outputs. */
    public static List<ProductionCandidateContinuation> fromBatch(
            int batchIndex, AutonomousProductionMiningRunner.MiningBatch batch, long seed) {
        return batch.binding().receipt().outputs().stream().map(output -> {
            var conjecture = batch.evidence().report().conjectures().stream()
                .filter(item -> item.conjectureId().equals(output.conjectureId())).findFirst().orElseThrow();
            return new ProductionCandidateContinuation(batchIndex, output, conjecture, seed);
        }).toList();
    }

    private ProductionCandidateContinuation(int batch, CandidateOutput output,
            OpenTargetConjecture conjecture, long seed) {
        if (!output.conjectureId().equals(conjecture.conjectureId())) {
            throw new IllegalArgumentException("formation output and conjecture disagree");
        }
        this.batch = batch;
        this.output = output;
        this.conjecture = conjecture;
        this.plan = AutonomousProductionLifecycleRunner.evaluationPlan(seed);
    }

    int batch() { return batch; }
    String id() { return output.outputBranchId(); }
    EvidenceStage nextStage() { return next; }
    int requiredTickets() { return next == EvidenceStage.VALIDATION ? 2 : 1; }

    public ObjectNode evidence() {
        ObjectNode row = JSON.createObjectNode();
        row.put("batch", batch).put("id", id()).put("conjectureId", conjecture.conjectureId())
            .put("lineageHash", output.lineageHash()).put("status", status);
        row.put("nextStage", next == null ? null : next.name());
        row.set("receipts", receipts.deepCopy());
        return row;
    }

    void advance(DispatchSink ledger) {
        switch (next) {
            case VALIDATION -> {
                Map<String, Long> attempts = new TreeMap<>();
                evaluation = OpenTargetConjectureEvaluator.observingCounterexampleAssignments(
                    source -> attempts.merge(source, 1L, Math::addExact)).evaluate(conjecture, plan);
                record(EvidenceStage.VALIDATION, ResourceKind.VALIDATION_CHECKS,
                    evaluation.executedPositiveHoldouts() + evaluation.executedNegativeHoldouts(),
                    AutonomousLifecycleEvidenceJson.validation(evaluation), ledger);
                ObjectNode assignments = receipts.putObject("evaluatedAssignmentsBySource");
                attempts.forEach(assignments::put);
                record(EvidenceStage.COUNTEREXAMPLE_SEARCH, ResourceKind.COUNTEREXAMPLE_ATTEMPTS,
                    attempts.values().stream().mapToLong(Long::longValue).sum(),
                    AutonomousLifecycleEvidenceJson.counterexample(conjecture.conjectureId(), evaluation), ledger);
                status = evaluation.status().name();
                next = evaluation.status() == EvaluationStatus.ACCEPTED_FOR_PROOF ? EvidenceStage.PROJECT_NOVELTY : null;
            }
            case PROJECT_NOVELTY -> {
                novelty = new OpenTargetConjectureNoveltyChecker().check(conjecture, new KnownRuleRepository(), List.of());
                record(next, ResourceKind.NOVELTY_COMPARISONS,
                    novelty.checkedActiveRules() + novelty.checkedPriorCandidates(),
                    AutonomousLifecycleEvidenceJson.novelty(novelty), ledger);
                status = novelty.status().name();
                next = novelty.status() == NoveltyStatus.NOVEL_WITHIN_PROJECT ? EvidenceStage.PROOF : null;
            }
            case PROOF -> {
                proof = new OpenTargetConjectureProofGate().evaluate(conjecture, evaluation);
                record(next, ResourceKind.PROOF_ATTEMPTS, proof.proofObligationEmitted() ? 1 : 0,
                    proof.toCanonicalJson(), ledger);
                if (proof.obligation() != null) {
                    receipts.set("solverObligation", parse(proof.obligation().toCanonicalJson()));
                }
                if (proof.result() != null) {
                    receipts.set("solverResult", parse(proof.result().toCanonicalJson()));
                }
                status = proof.proofStatus().name();
                next = proof.proofStatus() == ProofStatus.SYMBOLICALLY_VERIFIED ? EvidenceStage.LIFECYCLE_HANDOFF : null;
            }
            case LIFECYCLE_HANDOFF -> {
                var candidate = new OpenTargetHypothesisCandidateAdapter().adapt(
                    conjecture, evaluation, AutonomousProductionLifecycleRunner.LIFECYCLE_CREATED_AT);
                var decision = AutonomousCandidateLifecycleV2.decide(id(), novelty, proof, candidate);
                record(next, ResourceKind.LIFECYCLE_HANDOFFS,
                    decision.outcome() == AutonomousCandidateLifecycleV2.LifecycleOutcome.COMPLETED ? 1 : 0,
                    decision.toCanonicalJson(), ledger);
                receipts.set("lifecycleCandidate", parse(
                    AutonomousLifecycleEvidenceJson.lifecycleCandidate(candidate)));
                status = decision.outcome().name();
                next = null;
            }
            default -> throw new IllegalStateException("ineligible continuation " + next);
        }
    }

    private void record(EvidenceStage stage, ResourceKind kind, long work, String json,
            DispatchSink ledger) {
        receipts.set(stage.name(), parse(json));
        ledger.executed(stage, batch, id(), AutonomousResearchBriefV2.hash(json), kind, work);
    }

    static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid native artifact", exception);
        }
    }
}
