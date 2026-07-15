package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousCandidateLifecycleV2.LifecycleDecision;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateDecision;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.AggregateReceipt;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.CandidateOutput;
import de.regelsuche.experiments.autopilot.AutonomousEvidenceDagV2.SourceLink;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Versioned outer artifacts for the Autopilot v2 aggregate evidence DAG. */
public final class AutonomousCampaignArtifactsV2 {
    public static final String PLAN_SCHEMA =
        "regelsuche.autonomous-campaign-plan/v2";
    public static final String EXECUTION_SCHEMA =
        "regelsuche.autonomous-campaign-execution/v2";
    public static final String LINEAGE_SCHEMA =
        "regelsuche.autonomous-branch-lineage/v2";
    public static final String ROUND_SCHEMA =
        "regelsuche.autonomous-campaign-round/v2";

    private AutonomousCampaignArtifactsV2() {
    }

    public static CampaignPlan plan(
        AutonomousResearchBriefV2 brief,
        List<AggregateDecision> suppliedDecisions
    ) {
        Objects.requireNonNull(brief, "brief");
        List<AggregateDecision> decisions = suppliedDecisions == null
            ? List.of()
            : suppliedDecisions.stream()
                .sorted(Comparator.comparing(AggregateDecision::decisionId))
                .toList();
        requireUnique(
            decisions.stream().map(AggregateDecision::decisionId).toList(),
            "decision IDs");
        requireUnique(
            decisions.stream().map(AggregateDecision::contentHash).toList(),
            "decision hashes");
        decisions.forEach(decision -> {
            if (!brief.contentHash().equals(decision.briefHash())) {
                throw new IllegalArgumentException(
                    "v2 plan decision belongs to another research brief");
            }
        });
        String contentHash = AutonomousResearchBrief.hash(
            PLAN_SCHEMA
                + "\nbrief=" + brief.contentHash()
                + "\ndecisions=" + decisions.stream()
                    .map(AggregateDecision::contentHash).toList());
        return new CampaignPlan(
            PLAN_SCHEMA,
            brief.contentHash(),
            decisions,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static CampaignExecution execution(
        CampaignPlan plan,
        AggregateReceipt receipt
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(receipt, "receipt");
        if (plan.decisions().stream().noneMatch(decision ->
                decision.contentHash().equals(receipt.decisionHash()))) {
            throw new IllegalArgumentException(
                "aggregate receipt has no matching decision in the v2 plan");
        }
        String contentHash = AutonomousResearchBrief.hash(
            EXECUTION_SCHEMA
                + "\nplan=" + plan.contentHash()
                + "\ndecision=" + receipt.decisionHash()
                + "\nreceipt=" + receipt.contentHash()
                + "\nmining=" + receipt.miningEvidenceHash()
                + "\ndisposition=" + receipt.disposition().name()
                + "\noutputs=" + receipt.outputs().stream()
                    .map(CandidateOutput::outputBranchId).toList()
                + "\nrejected=" + receipt.rejectedClusters().stream()
                    .map(AutonomousEvidenceDagV2.RejectedCluster::contentHash).toList()
                + "\nexecuted=" + receipt.executedResources()
                + "\nskipped=" + receipt.skippedResources());
        return new CampaignExecution(
            EXECUTION_SCHEMA,
            plan.contentHash(),
            receipt.decisionHash(),
            receipt.contentHash(),
            receipt.miningEvidenceHash(),
            receipt.disposition().name(),
            receipt.outputs().stream().map(CandidateOutput::outputBranchId).toList(),
            receipt.rejectedClusters().stream()
                .map(AutonomousEvidenceDagV2.RejectedCluster::contentHash).toList(),
            receipt.executedResources(),
            receipt.skippedResources(),
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static BranchLineageArtifact lineage(AggregateReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        List<CandidateLineage> candidates = receipt.outputs().stream()
            .map(AutonomousCampaignArtifactsV2::candidateLineage)
            .sorted(Comparator.comparing(CandidateLineage::outputBranchId))
            .toList();
        String contentHash = AutonomousResearchBrief.hash(
            LINEAGE_SCHEMA
                + "\nreceipt=" + receipt.contentHash()
                + "\ndecision=" + receipt.decisionHash()
                + "\nmining=" + receipt.miningEvidenceHash()
                + "\ncandidates=" + candidates.stream()
                    .map(CandidateLineage::lineageHash).toList());
        return new BranchLineageArtifact(
            LINEAGE_SCHEMA,
            receipt.contentHash(),
            receipt.decisionHash(),
            receipt.miningEvidenceHash(),
            candidates,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static CampaignRound round(
        CampaignPlan currentPlan,
        CampaignExecution execution,
        BranchLineageArtifact lineage,
        List<LifecycleDecision> suppliedLifecycleDecisions,
        CampaignPlan nextPlan
    ) {
        Objects.requireNonNull(currentPlan, "currentPlan");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(lineage, "lineage");
        Objects.requireNonNull(nextPlan, "nextPlan");
        if (!currentPlan.contentHash().equals(execution.planHash())) {
            throw new IllegalArgumentException(
                "v2 execution belongs to another current plan");
        }
        if (!execution.aggregateReceiptHash().equals(lineage.aggregateReceiptHash())) {
            throw new IllegalArgumentException(
                "v2 lineage belongs to another aggregate receipt");
        }
        if (!currentPlan.briefHash().equals(nextPlan.briefHash())) {
            throw new IllegalArgumentException(
                "next v2 plan belongs to another research brief");
        }
        List<LifecycleDecision> lifecycleDecisions = suppliedLifecycleDecisions == null
            ? List.of()
            : suppliedLifecycleDecisions.stream()
                .sorted(Comparator.comparing(LifecycleDecision::candidateBranchId))
                .toList();
        requireUnique(
            lifecycleDecisions.stream()
                .map(LifecycleDecision::candidateBranchId).toList(),
            "lifecycle candidate branch IDs");
        Set<String> outputBranches = new TreeSet<>(execution.outputBranchIds());
        if (!outputBranches.containsAll(lifecycleDecisions.stream()
                .map(LifecycleDecision::candidateBranchId).toList())) {
            throw new IllegalArgumentException(
                "lifecycle decisions must reference outputs of this v2 execution");
        }
        String contentHash = AutonomousResearchBrief.hash(
            ROUND_SCHEMA
                + "\nplan=" + currentPlan.contentHash()
                + "\nexecution=" + execution.contentHash()
                + "\nlineage=" + lineage.contentHash()
                + "\nlifecycle=" + lifecycleDecisions.stream()
                    .map(LifecycleDecision::contentHash).toList()
                + "\nnextPlan=" + nextPlan.contentHash());
        return new CampaignRound(
            ROUND_SCHEMA,
            currentPlan.contentHash(),
            execution.contentHash(),
            lineage.contentHash(),
            lifecycleDecisions.stream().map(LifecycleDecision::contentHash).toList(),
            nextPlan.contentHash(),
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static CandidateLineage candidateLineage(CandidateOutput output) {
        return new CandidateLineage(
            output.outputBranchId(),
            output.conjectureId(),
            output.candidateEvidenceHash(),
            output.sources(),
            output.lineageHash());
    }

    public record CampaignPlan(
        String schema,
        String briefHash,
        List<AggregateDecision> decisions,
        boolean plannerDecisionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CampaignPlan {
            if (!PLAN_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported v2 campaign plan schema");
            }
            requireSha256(briefHash, "briefHash");
            decisions = decisions == null
                ? List.of()
                : decisions.stream()
                    .sorted(Comparator.comparing(AggregateDecision::decisionId))
                    .toList();
            if (plannerDecisionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "v2 campaign plan cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .array("decisions", array -> decisions.forEach(decision ->
                    array.objectValue(object -> object
                        .property("decisionId", decision.decisionId())
                        .property("decisionHash", decision.contentHash())
                        .property("stage", decision.stage().name())
                        .property("scope", decision.scope().name())
                        .stringArray("inputBranchIds", decision.inputs().stream()
                            .map(AutonomousEvidenceDagV2.ObservationBranch::branchId)
                            .toList()))))
                .property("plannerDecisionIsMathematicalEvidence",
                    plannerDecisionIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record CampaignExecution(
        String schema,
        String planHash,
        String decisionHash,
        String aggregateReceiptHash,
        String miningEvidenceHash,
        String disposition,
        List<String> outputBranchIds,
        List<String> rejectedClusterHashes,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources,
        boolean executionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CampaignExecution {
            if (!EXECUTION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported v2 execution schema");
            }
            requireSha256(planHash, "planHash");
            requireSha256(decisionHash, "decisionHash");
            requireSha256(aggregateReceiptHash, "aggregateReceiptHash");
            requireSha256(miningEvidenceHash, "miningEvidenceHash");
            requireText(disposition, "disposition");
            outputBranchIds = sortedText(outputBranchIds, "outputBranchIds");
            rejectedClusterHashes = sortedHashes(
                rejectedClusterHashes, "rejectedClusterHashes");
            executedResources = immutableResources(executedResources);
            skippedResources = immutableResources(skippedResources);
            if (executionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "v2 execution cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("planHash", planHash)
                .property("decisionHash", decisionHash)
                .property("aggregateReceiptHash", aggregateReceiptHash)
                .property("miningEvidenceHash", miningEvidenceHash)
                .property("disposition", disposition)
                .stringArray("outputBranchIds", outputBranchIds)
                .stringArray("rejectedClusterHashes", rejectedClusterHashes)
                .array("executedResources", array -> executedResources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> array.objectValue(object -> object
                        .property("resource", entry.getKey().name())
                        .property("amount", entry.getValue()))))
                .array("skippedResources", array -> skippedResources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> array.objectValue(object -> object
                        .property("resource", entry.getKey().name())
                        .property("amount", entry.getValue()))))
                .property("executionIsMathematicalEvidence",
                    executionIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record CandidateLineage(
        String outputBranchId,
        String candidateId,
        String candidateEvidenceHash,
        List<SourceLink> sources,
        String lineageHash
    ) {
        public CandidateLineage {
            requireText(outputBranchId, "outputBranchId");
            requireText(candidateId, "candidateId");
            requireSha256(candidateEvidenceHash, "candidateEvidenceHash");
            sources = sources == null
                ? List.of()
                : sources.stream()
                    .sorted(Comparator.comparing(SourceLink::observationId))
                    .toList();
            requireUnique(
                sources.stream().map(SourceLink::observationId).toList(),
                "lineage observation IDs");
            requireUnique(
                sources.stream().map(SourceLink::sourceBranchId).toList(),
                "lineage source branch IDs");
            requireSha256(lineageHash, "lineageHash");
        }
    }

    public record BranchLineageArtifact(
        String schema,
        String aggregateReceiptHash,
        String decisionHash,
        String miningEvidenceHash,
        List<CandidateLineage> candidates,
        boolean lineageIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public BranchLineageArtifact {
            if (!LINEAGE_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported v2 lineage schema");
            }
            requireSha256(aggregateReceiptHash, "aggregateReceiptHash");
            requireSha256(decisionHash, "decisionHash");
            requireSha256(miningEvidenceHash, "miningEvidenceHash");
            candidates = candidates == null
                ? List.of()
                : candidates.stream()
                    .sorted(Comparator.comparing(CandidateLineage::outputBranchId))
                    .toList();
            requireUnique(
                candidates.stream().map(CandidateLineage::outputBranchId).toList(),
                "lineage output branch IDs");
            if (lineageIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "v2 lineage metadata cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("aggregateReceiptHash", aggregateReceiptHash)
                .property("decisionHash", decisionHash)
                .property("miningEvidenceHash", miningEvidenceHash)
                .array("candidates", array -> candidates.forEach(candidate ->
                    array.objectValue(object -> object
                        .property("outputBranchId", candidate.outputBranchId())
                        .property("candidateId", candidate.candidateId())
                        .property("candidateEvidenceHash",
                            candidate.candidateEvidenceHash())
                        .array("sources", sources -> candidate.sources().forEach(source ->
                            sources.objectValue(item -> item
                                .property("observationId", source.observationId())
                                .property("sourceBranchId", source.sourceBranchId())
                                .property("familyId", source.familyId())
                                .property("snapshotHash", source.snapshotHash())
                                .property("evidenceHash", source.evidenceHash())
                                .property("observationBranchHash",
                                    source.observationBranchHash()))))
                        .property("lineageHash", candidate.lineageHash()))))
                .property("lineageIsMathematicalEvidence",
                    lineageIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record CampaignRound(
        String schema,
        String planHash,
        String executionHash,
        String lineageHash,
        List<String> lifecycleDecisionHashes,
        String nextPlanHash,
        boolean roundDecisionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CampaignRound {
            if (!ROUND_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported v2 campaign round schema");
            }
            requireSha256(planHash, "planHash");
            requireSha256(executionHash, "executionHash");
            requireSha256(lineageHash, "lineageHash");
            lifecycleDecisionHashes = sortedHashes(
                lifecycleDecisionHashes, "lifecycleDecisionHashes");
            requireSha256(nextPlanHash, "nextPlanHash");
            if (roundDecisionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "v2 campaign round cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("planHash", planHash)
                .property("executionHash", executionHash)
                .property("lineageHash", lineageHash)
                .stringArray("lifecycleDecisionHashes", lifecycleDecisionHashes)
                .property("nextPlanHash", nextPlanHash)
                .property("roundDecisionIsMathematicalEvidence",
                    roundDecisionIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static Map<ResourceKind, Long> immutableResources(
        Map<ResourceKind, Long> supplied
    ) {
        if (supplied == null || supplied.isEmpty()) {
            return Map.of();
        }
        EnumMap<ResourceKind, Long> result = new EnumMap<>(ResourceKind.class);
        supplied.forEach((resource, amount) -> {
            Objects.requireNonNull(resource, "resource");
            if (amount == null || amount < 0L) {
                throw new IllegalArgumentException(
                    "resource amounts must be non-negative");
            }
            if (amount > 0L) {
                result.put(resource, amount);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<String> sortedText(List<String> values, String name) {
        if (values == null) {
            return List.of();
        }
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " must contain non-blank values");
        }
        return values.stream().distinct().sorted().toList();
    }

    private static List<String> sortedHashes(List<String> values, String name) {
        List<String> result = values == null ? List.of() : values.stream().sorted().toList();
        result.forEach(value -> requireSha256(value, name));
        requireUnique(result, name);
        return result;
    }

    private static void requireUnique(List<String> values, String name) {
        if (new TreeSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(name + " must be unique");
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
