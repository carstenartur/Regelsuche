package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateDecision;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateReceipt;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateDraft;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.RejectedCluster;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.OpenTargetConjectureEvidence;
import de.regelsuche.mining.OpenTargetConjectureMiner.OpenTargetConjecture;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Production binding between canonical open-target mining evidence and the
 * Autopilot v2 aggregate receipt/DAG contracts.
 */
public final class OpenTargetAutopilotV2Binding {
    public static final String SCHEMA =
        "regelsuche.autonomous-open-target-mining-binding/v2";

    private OpenTargetAutopilotV2Binding() {
    }

    public static BindingResult completeCandidateFormation(
        AutonomousResearchBriefV2 brief,
        AggregateDecision decision,
        OpenTargetConjectureEvidence evidence,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources
    ) {
        Objects.requireNonNull(brief, "brief");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(evidence, "evidence");
        validateBindingInputs(brief, decision, evidence);

        String miningEvidenceHash = evidence.contentHash();
        List<CandidateDraft> candidates = evidence.report().conjectures().stream()
            .map(conjecture -> new CandidateDraft(
                conjecture.conjectureId(),
                candidateEvidenceHash(miningEvidenceHash, conjecture),
                conjecture.supportingObservationIds()))
            .toList();
        List<RejectedCluster> rejectedClusters = evidence.report().rejectedClusters().stream()
            .map(cluster -> RejectedCluster.create(
                AutonomousResearchBriefV2.hash(
                    miningEvidenceHash
                        + "|rejected=" + cluster.clusterSignature()
                        + "|support=" + cluster.observationIds()
                        + "|count=" + cluster.supportCount()
                        + "|alpha=" + cluster.distinctAlphaSupport()
                        + "|reason=" + cluster.reason()),
                cluster.reason(),
                cluster.observationIds()))
            .toList();

        AggregateReceipt receipt = AutonomousEvidenceDagV2.completeCandidateFormation(
            decision,
            miningEvidenceHash,
            candidates,
            rejectedClusters,
            executedResources,
            skippedResources);
        String contentHash = AutonomousResearchBriefV2.hash(
            SCHEMA
                + "\ncampaign=" + evidence.context().campaignId()
                + "\ninventory=" + evidence.context().ruleInventoryHash()
                + "\nminingEvidence=" + miningEvidenceHash
                + "\ndecision=" + decision.contentHash()
                + "\nreceipt=" + receipt.contentHash()
                + "\nconjectures=" + candidates.stream()
                    .map(CandidateDraft::conjectureId).toList()
                + "\nrejected=" + rejectedClusters.stream()
                    .map(RejectedCluster::contentHash).toList());
        return new BindingResult(
            SCHEMA,
            evidence.context().campaignId(),
            evidence.context().ruleInventoryHash(),
            miningEvidenceHash,
            decision.contentHash(),
            receipt,
            evidence.report().conjectures().size(),
            evidence.report().rejectedClusters().size(),
            false,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static void validateBindingInputs(
        AutonomousResearchBriefV2 brief,
        AggregateDecision decision,
        OpenTargetConjectureEvidence evidence
    ) {
        if (!brief.contentHash().equals(decision.briefHash())) {
            throw new IllegalArgumentException(
                "aggregate decision belongs to another v2 research brief");
        }
        if (!brief.inventoryHash().equals(evidence.context().ruleInventoryHash())) {
            throw new IllegalArgumentException(
                "open-target mining inventory does not match the v2 brief");
        }
        if (evidence.report().targetProvided()) {
            throw new IllegalArgumentException(
                "Autopilot v2 candidate formation accepts only target-free mining evidence");
        }
        Set<String> plannedObservations = decision.inputs().stream()
            .map(AutonomousEvidenceDagV2.ObservationBranch::observationId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        Set<String> minedObservations = evidence.context().seeds().stream()
            .map(OpenTargetConjectureEvidence.SeedProvenance::observationId)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (!plannedObservations.equals(minedObservations)) {
            throw new IllegalArgumentException(
                "aggregate decision observations do not match canonical mining evidence: "
                    + plannedObservations + " != " + minedObservations);
        }
    }

    private static String candidateEvidenceHash(
        String miningEvidenceHash,
        OpenTargetConjecture conjecture
    ) {
        return AutonomousResearchBriefV2.hash(
            miningEvidenceHash
                + "|conjecture=" + conjecture.conjectureId()
                + "|patterns=" + conjecture.leftPattern() + "->" + conjecture.rightPattern()
                + "|support=" + conjecture.supportingObservationIds()
                + "|alpha=" + conjecture.distinctAlphaSupport()
                + "|families=" + conjecture.postHocFamilies());
    }

    public record BindingResult(
        String schema,
        String campaignId,
        String ruleInventoryHash,
        String miningEvidenceHash,
        String decisionHash,
        AggregateReceipt receipt,
        int conjectureCount,
        int rejectedClusterCount,
        boolean targetProvided,
        boolean bindingIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public BindingResult {
            if (!SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported production binding schema");
            }
            requireText(campaignId, "campaignId");
            requireSha256(ruleInventoryHash, "ruleInventoryHash");
            requireSha256(miningEvidenceHash, "miningEvidenceHash");
            requireSha256(decisionHash, "decisionHash");
            receipt = Objects.requireNonNull(receipt, "receipt");
            if (!decisionHash.equals(receipt.decisionHash())) {
                throw new IllegalArgumentException(
                    "production binding receipt belongs to another aggregate decision");
            }
            if (!miningEvidenceHash.equals(receipt.miningEvidenceHash())) {
                throw new IllegalArgumentException(
                    "production binding receipt does not retain the mining evidence hash");
            }
            if (conjectureCount < 0 || rejectedClusterCount < 0) {
                throw new IllegalArgumentException("binding counts must be non-negative");
            }
            if (targetProvided) {
                throw new IllegalArgumentException(
                    "production Autopilot binding must remain target-free");
            }
            if (bindingIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "the production binding is lineage metadata, not mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("campaignId", campaignId)
                .property("ruleInventoryHash", ruleInventoryHash)
                .property("miningEvidenceHash", miningEvidenceHash)
                .property("decisionHash", decisionHash)
                .property("receiptHash", receipt.contentHash())
                .property("conjectureCount", conjectureCount)
                .property("rejectedClusterCount", rejectedClusterCount)
                .property("targetProvided", targetProvided)
                .property("bindingIsMathematicalEvidence", bindingIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .stringArray("outputBranchIds", receipt.outputs().stream()
                    .map(AutonomousEvidenceDagV2.CandidateOutput::outputBranchId)
                    .toList())
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
