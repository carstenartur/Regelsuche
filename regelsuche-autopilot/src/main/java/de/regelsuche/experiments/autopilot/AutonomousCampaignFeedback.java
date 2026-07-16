package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousCampaignArtifactsV2.CampaignPlan;
import de.regelsuche.experiments.autopilot.AutonomousProductionLifecycleRunner.LifecycleRun;
import de.regelsuche.json.JsonWriter;
import java.util.List;
import java.util.Objects;

/** Deterministic post-receipt feedback and reallocation decision. */
public final class AutonomousCampaignFeedback {
    public static final String SCHEMA =
        "regelsuche.autonomous-feedback-reallocation/v2";

    private AutonomousCampaignFeedback() {
    }

    public static FeedbackDecision complete(
        LifecycleRun lifecycle,
        CampaignPlan nextPlan
    ) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(nextPlan, "nextPlan");
        var mining = lifecycle.mining();
        var fullReceipt = mining.fullBatch().binding().receipt();
        var rejectionReceipt = mining.rejectionBatch().binding().receipt();
        if (!nextPlan.decisions().isEmpty()) {
            throw new IllegalArgumentException(
                "completed pinned campaign cannot allocate another Autopilot decision");
        }
        if (fullReceipt.outputs().size() != 1
                || !fullReceipt.outputs().getFirst().outputBranchId()
                    .equals(lifecycle.candidateBranchId())
                || !rejectionReceipt.outputs().isEmpty()
                || rejectionReceipt.rejectedClusters().isEmpty()) {
            throw new IllegalArgumentException(
                "feedback requires one completed candidate and explicit rejection evidence");
        }
        List<String> completedBranches = List.of(lifecycle.candidateBranchId());
        List<String> rejectedClusters = rejectionReceipt.rejectedClusters().stream()
            .map(AutonomousEvidenceDagV2.RejectedCluster::contentHash)
            .sorted()
            .toList();
        List<String> reasons = List.of(
            "ALPHA_EQUIVALENT_CLUSTER_REJECTED",
            "CANDIDATE_LIFECYCLE_COMPLETED",
            "NO_ELIGIBLE_AUTOPILOT_BRANCHES",
            "UNUSED_BUDGET_RETAINED");
        String contentHash = AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\nbrief=" + mining.generation().brief().contentHash()
                + "\ncurrentPlan=" + mining.plan().contentHash()
                + "\nsuccessfulExecution=" + mining.fullBatch().execution().contentHash()
                + "\nrejectedExecution=" + mining.rejectionBatch().execution().contentHash()
                + "\nlifecycle=" + lifecycle.lifecycleDecision().contentHash()
                + "\nformationReceipt=" + mining.formationReceipt().contentHash()
                + "\nstageLedger=" + lifecycle.stageLedger().contentHash()
                + "\ncompletedBranches=" + completedBranches
                + "\nrejectedClusters=" + rejectedClusters
                + "\nnextPlan=" + nextPlan.contentHash()
                + "\nreasons=" + reasons
                + "\ndisposition=CAMPAIGN_COMPLETE");
        return new FeedbackDecision(
            SCHEMA,
            mining.generation().brief().contentHash(),
            mining.plan().contentHash(),
            mining.fullBatch().execution().contentHash(),
            mining.rejectionBatch().execution().contentHash(),
            lifecycle.lifecycleDecision().contentHash(),
            mining.formationReceipt().contentHash(),
            lifecycle.stageLedger().contentHash(),
            completedBranches,
            rejectedClusters,
            List.of(),
            nextPlan.contentHash(),
            0,
            "CAMPAIGN_COMPLETE",
            reasons,
            true,
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public record FeedbackDecision(
        String schema,
        String briefHash,
        String currentPlanHash,
        String successfulExecutionHash,
        String rejectedExecutionHash,
        String lifecycleDecisionHash,
        String candidateFormationReceiptHash,
        String downstreamStageLedgerHash,
        List<String> completedCandidateBranchIds,
        List<String> rejectedClusterHashes,
        List<String> eligibleBranchIds,
        String nextPlanHash,
        int nextDecisionCount,
        String disposition,
        List<String> reasonCodes,
        boolean usesOnlyRetainedEvidence,
        boolean feedbackIsMathematicalEvidence,
        boolean externalNoveltyEvaluated,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public FeedbackDecision {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported feedback schema");
            }
            requireSha256(briefHash, "briefHash");
            requireSha256(currentPlanHash, "currentPlanHash");
            requireSha256(successfulExecutionHash, "successfulExecutionHash");
            requireSha256(rejectedExecutionHash, "rejectedExecutionHash");
            requireSha256(lifecycleDecisionHash, "lifecycleDecisionHash");
            requireSha256(candidateFormationReceiptHash,
                "candidateFormationReceiptHash");
            requireSha256(downstreamStageLedgerHash, "downstreamStageLedgerHash");
            completedCandidateBranchIds = sortedText(
                completedCandidateBranchIds, "completedCandidateBranchIds");
            rejectedClusterHashes = sortedHashes(
                rejectedClusterHashes, "rejectedClusterHashes");
            eligibleBranchIds = sortedText(eligibleBranchIds, "eligibleBranchIds");
            requireSha256(nextPlanHash, "nextPlanHash");
            if (completedCandidateBranchIds.size() != 1
                    || rejectedClusterHashes.isEmpty()
                    || !eligibleBranchIds.isEmpty()
                    || nextDecisionCount != 0
                    || !"CAMPAIGN_COMPLETE".equals(disposition)) {
                throw new IllegalArgumentException(
                    "completed feedback must retain completion, rejection and an empty next plan");
            }
            reasonCodes = sortedText(reasonCodes, "reasonCodes");
            if (!usesOnlyRetainedEvidence
                    || feedbackIsMathematicalEvidence
                    || externalNoveltyEvaluated) {
                throw new IllegalArgumentException(
                    "feedback must use retained evidence without creating truth or novelty claims");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .property("currentPlanHash", currentPlanHash)
                .property("successfulExecutionHash", successfulExecutionHash)
                .property("rejectedExecutionHash", rejectedExecutionHash)
                .property("lifecycleDecisionHash", lifecycleDecisionHash)
                .property("candidateFormationReceiptHash",
                    candidateFormationReceiptHash)
                .property("downstreamStageLedgerHash", downstreamStageLedgerHash)
                .stringArray("completedCandidateBranchIds",
                    completedCandidateBranchIds)
                .stringArray("rejectedClusterHashes", rejectedClusterHashes)
                .stringArray("eligibleBranchIds", eligibleBranchIds)
                .property("nextPlanHash", nextPlanHash)
                .property("nextDecisionCount", nextDecisionCount)
                .property("disposition", disposition)
                .stringArray("reasonCodes", reasonCodes)
                .property("usesOnlyRetainedEvidence", usesOnlyRetainedEvidence)
                .property("feedbackIsMathematicalEvidence",
                    feedbackIsMathematicalEvidence)
                .property("externalNoveltyEvaluated", externalNoveltyEvaluated)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static List<String> sortedText(List<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " contains blank values");
        }
        return values.stream().distinct().sorted().toList();
    }

    private static List<String> sortedHashes(List<String> values, String name) {
        List<String> result = sortedText(values, name);
        result.forEach(value -> requireSha256(value, name));
        return result;
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
