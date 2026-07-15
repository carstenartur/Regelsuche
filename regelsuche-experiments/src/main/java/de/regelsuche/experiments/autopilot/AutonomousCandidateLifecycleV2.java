package de.regelsuche.experiments.autopilot;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyStatus;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofReport;
import de.regelsuche.mining.OpenTargetConjectureProofGate.ProofStatus;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic mapping from project novelty, proof and conservative lifecycle
 * handoff evidence to an Autopilot v2 branch outcome.
 *
 * <p>Completion here means that the configured Autopilot stages have finished.
 * It is not promotion, publication, external novelty or a formal-proof claim.</p>
 */
public final class AutonomousCandidateLifecycleV2 {
    public static final String SCHEMA =
        "regelsuche.autonomous-candidate-lifecycle/v2";

    private AutonomousCandidateLifecycleV2() {
    }

    public static LifecycleDecision decide(
        String candidateBranchId,
        NoveltyReport novelty,
        ProofReport proof,
        HypothesisCandidate lifecycleCandidate
    ) {
        requireText(candidateBranchId, "candidateBranchId");
        Objects.requireNonNull(novelty, "novelty");
        requireText(novelty.conjectureId(), "novelty.conjectureId");
        NoveltyStatus noveltyStatus = Objects.requireNonNull(
            novelty.status(), "novelty.status");

        LifecycleOutcome outcome;
        StageDisposition proofDisposition;
        StageDisposition handoffDisposition;
        List<String> blockers;
        String proofEvidenceHash = "";
        String lifecycleCandidateId = "";

        switch (noveltyStatus) {
            case EXACT_DUPLICATE, ALPHA_EQUIVALENT_DUPLICATE -> {
                requireNoDownstreamEvidence(proof, lifecycleCandidate, "duplicate novelty outcome");
                outcome = LifecycleOutcome.DUPLICATE;
                proofDisposition = StageDisposition.NOT_RUN_TERMINAL;
                handoffDisposition = StageDisposition.NOT_RUN_TERMINAL;
                blockers = List.of("project-internal duplicate: " + noveltyStatus.name());
            }
            case INCONCLUSIVE_UNPARSEABLE -> {
                requireNoDownstreamEvidence(proof, lifecycleCandidate, "inconclusive novelty outcome");
                outcome = LifecycleOutcome.INCOMPLETE;
                proofDisposition = StageDisposition.NOT_RUN_BLOCKED;
                handoffDisposition = StageDisposition.NOT_RUN_BLOCKED;
                blockers = List.of("project novelty is inconclusive");
            }
            case NOVEL_WITHIN_PROJECT -> {
                if (proof == null) {
                    if (lifecycleCandidate != null) {
                        throw new IllegalArgumentException(
                            "lifecycle handoff cannot precede proof evaluation");
                    }
                    outcome = LifecycleOutcome.INCOMPLETE;
                    proofDisposition = StageDisposition.NOT_RUN;
                    handoffDisposition = StageDisposition.NOT_RUN_BLOCKED;
                    blockers = List.of("proof result is missing");
                } else {
                    validateProof(novelty.conjectureId(), proof);
                    proofEvidenceHash = proof.evidenceHash();
                    switch (proof.proofStatus()) {
                        case REFUTED -> {
                            requireNoLifecycleCandidate(lifecycleCandidate, "refuted proof outcome");
                            outcome = LifecycleOutcome.DISPROVED;
                            proofDisposition = StageDisposition.COMPLETED_TERMINAL;
                            handoffDisposition = StageDisposition.NOT_RUN_TERMINAL;
                            blockers = List.of("proof backend refuted the conjecture");
                        }
                        case INCONCLUSIVE -> {
                            requireNoLifecycleCandidate(lifecycleCandidate, "inconclusive proof outcome");
                            outcome = LifecycleOutcome.INCOMPLETE;
                            proofDisposition = StageDisposition.COMPLETED_INCONCLUSIVE;
                            handoffDisposition = StageDisposition.NOT_RUN_BLOCKED;
                            blockers = List.of("proof result is inconclusive");
                        }
                        case NOT_RUN -> {
                            requireNoLifecycleCandidate(lifecycleCandidate, "proof-not-run outcome");
                            outcome = LifecycleOutcome.INCOMPLETE;
                            proofDisposition = StageDisposition.NOT_RUN_BLOCKED;
                            handoffDisposition = StageDisposition.NOT_RUN_BLOCKED;
                            blockers = List.of("proof gate did not run");
                        }
                        case SYMBOLICALLY_VERIFIED -> {
                            proofDisposition = StageDisposition.COMPLETED;
                            if (lifecycleCandidate == null) {
                                outcome = LifecycleOutcome.INCOMPLETE;
                                handoffDisposition = StageDisposition.NOT_RUN;
                                blockers = List.of("lifecycle handoff is missing");
                            } else {
                                validateLifecycleCandidate(novelty.conjectureId(), lifecycleCandidate);
                                lifecycleCandidateId = lifecycleCandidate.id();
                                outcome = LifecycleOutcome.COMPLETED;
                                handoffDisposition = StageDisposition.COMPLETED;
                                blockers = List.of();
                            }
                        }
                    }
                }
            }
            default -> throw new IllegalStateException(
                "unhandled novelty status: " + noveltyStatus);
        }

        String contentHash = AutonomousResearchBrief.hash(
            SCHEMA
                + "\nbranch=" + candidateBranchId
                + "\ncandidate=" + novelty.conjectureId()
                + "\nnovelty=" + noveltyStatus.name()
                + "\nproof=" + proofDisposition.name()
                + "\nproofEvidence=" + proofEvidenceHash
                + "\nhandoff=" + handoffDisposition.name()
                + "\nlifecycleCandidate=" + lifecycleCandidateId
                + "\noutcome=" + outcome.name()
                + "\nblockers=" + blockers);
        return new LifecycleDecision(
            SCHEMA,
            candidateBranchId,
            novelty.conjectureId(),
            noveltyStatus,
            proofDisposition,
            proofEvidenceHash,
            handoffDisposition,
            lifecycleCandidateId,
            outcome,
            outcome == LifecycleOutcome.DUPLICATE
                || outcome == LifecycleOutcome.DISPROVED,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            blockers,
            contentHash);
    }

    private static void validateProof(String conjectureId, ProofReport proof) {
        if (!conjectureId.equals(proof.conjectureId())) {
            throw new IllegalArgumentException(
                "novelty and proof evidence refer to different candidates");
        }
        Objects.requireNonNull(proof.proofStatus(), "proof.proofStatus");
        requireSha256(proof.evidenceHash(), "proof.evidenceHash");
    }

    private static void validateLifecycleCandidate(
        String conjectureId,
        HypothesisCandidate lifecycleCandidate
    ) {
        if (!conjectureId.equals(lifecycleCandidate.id())) {
            throw new IllegalArgumentException(
                "lifecycle handoff refers to another candidate");
        }
        if (lifecycleCandidate.proofStatus() != CandidateProofStatus.VALIDATED_BY_EXAMPLES) {
            throw new IllegalArgumentException(
                "open-target lifecycle handoff must remain conservatively validated by examples");
        }
        if (Boolean.TRUE.equals(lifecycleCandidate.counterexampleStatus())) {
            throw new IllegalArgumentException(
                "lifecycle handoff cannot contain a counterexample");
        }
    }

    private static void requireNoDownstreamEvidence(
        ProofReport proof,
        HypothesisCandidate lifecycleCandidate,
        String reason
    ) {
        if (proof != null || lifecycleCandidate != null) {
            throw new IllegalArgumentException(reason + " must stop downstream stages");
        }
    }

    private static void requireNoLifecycleCandidate(
        HypothesisCandidate lifecycleCandidate,
        String reason
    ) {
        if (lifecycleCandidate != null) {
            throw new IllegalArgumentException(reason + " cannot create a lifecycle candidate");
        }
    }

    public enum LifecycleOutcome {
        COMPLETED,
        DUPLICATE,
        DISPROVED,
        INCOMPLETE
    }

    public enum StageDisposition {
        COMPLETED,
        COMPLETED_TERMINAL,
        COMPLETED_INCONCLUSIVE,
        NOT_RUN,
        NOT_RUN_BLOCKED,
        NOT_RUN_TERMINAL
    }

    public record LifecycleDecision(
        String schema,
        String candidateBranchId,
        String candidateId,
        NoveltyStatus noveltyStatus,
        StageDisposition proofDisposition,
        String proofEvidenceHash,
        StageDisposition lifecycleHandoffDisposition,
        String lifecycleCandidateId,
        LifecycleOutcome outcome,
        boolean terminal,
        boolean promotionAttempted,
        boolean publicationAttempted,
        String promotionStatus,
        String publicEvidenceStatus,
        List<String> blockers,
        String contentHash
    ) {
        public LifecycleDecision {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported lifecycle schema");
            }
            requireText(candidateBranchId, "candidateBranchId");
            requireText(candidateId, "candidateId");
            Objects.requireNonNull(noveltyStatus, "noveltyStatus");
            Objects.requireNonNull(proofDisposition, "proofDisposition");
            proofEvidenceHash = proofEvidenceHash == null ? "" : proofEvidenceHash;
            if (!proofEvidenceHash.isEmpty()) {
                requireSha256(proofEvidenceHash, "proofEvidenceHash");
            }
            Objects.requireNonNull(lifecycleHandoffDisposition,
                "lifecycleHandoffDisposition");
            lifecycleCandidateId = lifecycleCandidateId == null ? "" : lifecycleCandidateId;
            Objects.requireNonNull(outcome, "outcome");
            if (terminal != (outcome == LifecycleOutcome.DUPLICATE
                    || outcome == LifecycleOutcome.DISPROVED)) {
                throw new IllegalArgumentException(
                    "only duplicate and disproved outcomes are terminal");
            }
            if (promotionAttempted || publicationAttempted) {
                throw new IllegalArgumentException(
                    "Autopilot lifecycle handoff cannot promote or publish");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            blockers = blockers == null
                ? List.of()
                : blockers.stream().distinct().sorted().toList();
            if (outcome == LifecycleOutcome.COMPLETED && !blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "completed lifecycle decision cannot contain blockers");
            }
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("candidateBranchId", candidateBranchId)
                .property("candidateId", candidateId)
                .property("noveltyStatus", noveltyStatus.name())
                .property("proofDisposition", proofDisposition.name())
                .property("proofEvidenceHash", proofEvidenceHash)
                .property("lifecycleHandoffDisposition",
                    lifecycleHandoffDisposition.name())
                .property("lifecycleCandidateId", lifecycleCandidateId)
                .property("outcome", outcome.name())
                .property("terminal", terminal)
                .property("promotionAttempted", promotionAttempted)
                .property("publicationAttempted", publicationAttempted)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .stringArray("blockers", blockers)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireSha256(String value, String name) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be SHA-256");
        }
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }
}
