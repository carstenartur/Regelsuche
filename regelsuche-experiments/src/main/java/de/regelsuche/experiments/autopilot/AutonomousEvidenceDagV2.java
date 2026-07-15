package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable aggregate branch, plan, receipt and lineage contracts for Autopilot v2.
 */
public final class AutonomousEvidenceDagV2 {
    public static final String PLAN_SCHEMA =
        "regelsuche.autonomous-campaign-plan/v2";
    public static final String EXECUTION_SCHEMA =
        "regelsuche.autonomous-campaign-execution/v2";
    public static final String ROUND_SCHEMA =
        "regelsuche.autonomous-campaign-round/v2";
    public static final String LINEAGE_SCHEMA =
        "regelsuche.autonomous-branch-lineage/v2";

    private AutonomousEvidenceDagV2() {
    }

    public static AggregateDecision candidateFormationDecision(
        AutonomousResearchBriefV2 brief,
        List<EvidenceBranch> suppliedInputs,
        Map<ResourceKind, Long> plannedResources
    ) {
        Objects.requireNonNull(brief, "brief");
        List<EvidenceBranch> inputs = orderedBranches(suppliedInputs);
        List<String> blockers = decisionBlockers(brief, inputs);
        DecisionStatus status = blockers.isEmpty()
            ? DecisionStatus.READY
            : DecisionStatus.BLOCKED;
        Map<ResourceKind, Long> resources = immutableResources(plannedResources);
        if (status == DecisionStatus.READY && resources.isEmpty()) {
            throw new IllegalArgumentException(
                "ready aggregate decision requires planned resources");
        }
        String seedMaterial = PLAN_SCHEMA
            + "\nbrief=" + brief.contentHash()
            + "\nstage=" + EvidenceStage.CANDIDATE_FORMATION.name()
            + "\ninputs=" + inputs.stream()
                .map(EvidenceBranch::identityMaterial).toList()
            + "\nnamespace=" + brief.outputNamespace();
        String decisionId = "aggregate." + shortHash(
            AutonomousResearchBrief.hash(seedMaterial));
        String contentHash = AutonomousResearchBrief.hash(
            seedMaterial
                + "\ndecisionId=" + decisionId
                + "\nminimumInputs=" + brief.minimumAggregateInputs()
                + "\nminimumSupport=" + brief.minimumSupportDiversity()
                + "\nminimumAlpha=" + brief.minimumAlphaDiversity()
                + "\nresources=" + resources
                + "\nstatus=" + status.name()
                + "\nblockers=" + blockers);
        return new AggregateDecision(
            decisionId,
            brief.contentHash(),
            EvidenceStage.CANDIDATE_FORMATION,
            DecisionScope.AGGREGATE,
            inputs,
            brief.minimumAggregateInputs(),
            brief.minimumSupportDiversity(),
            brief.minimumAlphaDiversity(),
            BranchType.CONJECTURE_CANDIDATE,
            brief.outputNamespace(),
            resources,
            status,
            blockers,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
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
        decisions.forEach(decision -> {
            if (!brief.contentHash().equals(decision.briefHash())) {
                throw new IllegalArgumentException(
                    "aggregate decision belongs to a different v2 brief");
            }
        });
        String material = PLAN_SCHEMA
            + "\nbrief=" + brief.contentHash()
            + "\ndecisions=" + decisions.stream()
                .map(AggregateDecision::contentHash).toList();
        return new CampaignPlan(
            PLAN_SCHEMA,
            brief.contentHash(),
            decisions,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            AutonomousResearchBrief.hash(material));
    }

    public static AggregateExecutionReceipt executeFormation(
        AggregateDecision decision,
        MiningReport report
    ) {
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(report, "report");
        Map<String, EvidenceBranch> inputs = new LinkedHashMap<>();
        decision.inputBranches().forEach(branch -> inputs.put(branch.branchId(), branch));

        List<OutputBranch> outputs = new ArrayList<>();
        List<BranchLineage> lineages = new ArrayList<>();
        List<CandidateRejection> candidateRejections = new ArrayList<>();
        if (decision.status() == DecisionStatus.BLOCKED) {
            if (!report.candidates().isEmpty()) {
                throw new IllegalArgumentException(
                    "blocked aggregate decision cannot create candidate outputs");
            }
        } else {
            for (MiningCandidate candidate : report.candidates()) {
                CandidateValidation validation = validateCandidate(
                    decision, candidate, inputs);
                if (!validation.blockers().isEmpty()) {
                    candidateRejections.add(new CandidateRejection(
                        candidate.candidateId(),
                        candidate.convergenceEvidenceHash(),
                        validation.blockers()));
                    continue;
                }
                String outputBranchId = decision.outputNamespace()
                    + ':' + candidate.candidateId();
                BranchLineage lineage = lineage(
                    decision, report, candidate, outputBranchId, validation.sources());
                String snapshotHash = AutonomousResearchBrief.hash(
                    "candidate-branch/v2|" + outputBranchId + '|'
                        + candidate.convergenceEvidenceHash() + '|'
                        + lineage.lineageHash());
                outputs.add(new OutputBranch(
                    outputBranchId,
                    BranchType.CONJECTURE_CANDIDATE,
                    snapshotHash,
                    candidate.convergenceEvidenceHash(),
                    CandidateBranchStatus.ELIGIBLE_INCOMPLETE));
                lineages.add(lineage);
            }
        }
        outputs.sort(Comparator.comparing(OutputBranch::branchId));
        lineages.sort(Comparator.comparing(BranchLineage::outputBranchId));
        candidateRejections.sort(Comparator.comparing(CandidateRejection::candidateId));
        ExecutionStatus status = decision.status() == DecisionStatus.BLOCKED
            ? ExecutionStatus.BLOCKED
            : outputs.isEmpty()
                ? ExecutionStatus.ZERO_OUTPUT
                : ExecutionStatus.COMPLETED;
        String contentHash = AutonomousResearchBrief.hash(
            EXECUTION_SCHEMA
                + "\ndecision=" + decision.contentHash()
                + "\nreport=" + report.reportHash()
                + "\noutputs=" + outputs.stream()
                    .map(OutputBranch::identityMaterial).toList()
                + "\nlineages=" + lineages.stream()
                    .map(BranchLineage::lineageHash).toList()
                + "\nrejectedClusters=" + report.rejectedClusters().stream()
                    .map(RejectedCluster::identityMaterial).toList()
                + "\ncandidateRejections=" + candidateRejections.stream()
                    .map(CandidateRejection::identityMaterial).toList()
                + "\nstatus=" + status.name());
        return new AggregateExecutionReceipt(
            EXECUTION_SCHEMA,
            decision.contentHash(),
            report.reportHash(),
            report.campaignId(),
            report.ruleInventoryHash(),
            status,
            List.copyOf(outputs),
            List.copyOf(lineages),
            report.rejectedClusters(),
            List.copyOf(candidateRejections),
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static CampaignRound round(
        CampaignPlan plan,
        AggregateExecutionReceipt execution,
        String nextPlanHash
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(execution, "execution");
        requireSha256(nextPlanHash, "nextPlanHash");
        if (plan.decisions().stream().noneMatch(decision ->
                decision.contentHash().equals(execution.decisionHash()))) {
            throw new IllegalArgumentException(
                "execution is not bound to a decision in the v2 plan");
        }
        String lineageDagHash = AutonomousResearchBrief.hash(
            LINEAGE_SCHEMA + "\nlineages=" + execution.lineages().stream()
                .map(BranchLineage::lineageHash).toList());
        String contentHash = AutonomousResearchBrief.hash(
            ROUND_SCHEMA
                + "\nplan=" + plan.contentHash()
                + "\nexecution=" + execution.contentHash()
                + "\nlineageDag=" + lineageDagHash
                + "\nnextPlan=" + nextPlanHash);
        return new CampaignRound(
            ROUND_SCHEMA,
            plan.contentHash(),
            execution.contentHash(),
            lineageDagHash,
            nextPlanHash,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    private static List<String> decisionBlockers(
        AutonomousResearchBriefV2 brief,
        List<EvidenceBranch> inputs
    ) {
        List<String> blockers = new ArrayList<>();
        if (inputs.size() < brief.minimumAggregateInputs()) {
            blockers.add("insufficient-input-count=" + inputs.size()
                + '<' + brief.minimumAggregateInputs());
        }
        long distinctIds = inputs.stream().map(EvidenceBranch::branchId).distinct().count();
        if (distinctIds != inputs.size()) {
            blockers.add("duplicate-input-branch-id");
        }
        if (inputs.stream().anyMatch(branch -> branch.type() != BranchType.OBSERVATION)) {
            blockers.add("non-observation-input");
        }
        long families = inputs.stream().map(EvidenceBranch::familyId).distinct().count();
        if (families < brief.minimumSupportDiversity()) {
            blockers.add("insufficient-family-diversity=" + families
                + '<' + brief.minimumSupportDiversity());
        }
        long alphas = inputs.stream().map(EvidenceBranch::alphaFingerprint)
            .distinct().count();
        if (alphas < brief.minimumAlphaDiversity()) {
            blockers.add("insufficient-alpha-diversity=" + alphas
                + '<' + brief.minimumAlphaDiversity());
        }
        return blockers.stream().distinct().sorted().toList();
    }

    private static CandidateValidation validateCandidate(
        AggregateDecision decision,
        MiningCandidate candidate,
        Map<String, EvidenceBranch> inputs
    ) {
        List<String> blockers = new ArrayList<>();
        List<String> supportingIds = candidate.supportingObservationIds();
        if (new TreeSet<>(supportingIds).size() != supportingIds.size()) {
            blockers.add("duplicate-supporting-observation");
        }
        List<EvidenceBranch> sources = supportingIds.stream()
            .map(inputs::get)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(EvidenceBranch::branchId))
            .toList();
        if (sources.size() != supportingIds.size()) {
            blockers.add("support-not-in-aggregate-input");
        }
        if (sources.size() < decision.minimumSupportDiversity()) {
            blockers.add("insufficient-candidate-support=" + sources.size()
                + '<' + decision.minimumSupportDiversity());
        }
        long alphaSupport = sources.stream().map(EvidenceBranch::alphaFingerprint)
            .distinct().count();
        if (alphaSupport < decision.minimumAlphaDiversity()) {
            blockers.add("insufficient-candidate-alpha-support=" + alphaSupport
                + '<' + decision.minimumAlphaDiversity());
        }
        return new CandidateValidation(
            sources,
            blockers.stream().distinct().sorted().toList());
    }

    private static BranchLineage lineage(
        AggregateDecision decision,
        MiningReport report,
        MiningCandidate candidate,
        String outputBranchId,
        List<EvidenceBranch> sources
    ) {
        List<SourceBranchRef> refs = sources.stream()
            .map(source -> new SourceBranchRef(
                source.branchId(), source.snapshotHash(), source.evidenceHash()))
            .sorted(Comparator.comparing(SourceBranchRef::branchId))
            .toList();
        String hash = AutonomousResearchBrief.hash(
            LINEAGE_SCHEMA
                + "\noutput=" + outputBranchId
                + "\ncandidate=" + candidate.candidateId()
                + "\ndecision=" + decision.contentHash()
                + "\nreport=" + report.reportHash()
                + "\nconvergence=" + candidate.convergenceEvidenceHash()
                + "\nsources=" + refs.stream()
                    .map(SourceBranchRef::identityMaterial).toList());
        return new BranchLineage(
            LINEAGE_SCHEMA,
            outputBranchId,
            BranchType.CONJECTURE_CANDIDATE,
            candidate.candidateId(),
            decision.contentHash(),
            report.reportHash(),
            candidate.convergenceEvidenceHash(),
            refs,
            hash);
    }

    private static List<EvidenceBranch> orderedBranches(List<EvidenceBranch> supplied) {
        Objects.requireNonNull(supplied, "inputBranches");
        if (supplied.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("inputBranches must not contain null");
        }
        return supplied.stream()
            .sorted(Comparator.comparing(EvidenceBranch::branchId)
                .thenComparing(EvidenceBranch::snapshotHash))
            .toList();
    }

    private static Map<ResourceKind, Long> immutableResources(
        Map<ResourceKind, Long> values
    ) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        EnumMap<ResourceKind, Long> ordered = new EnumMap<>(ResourceKind.class);
        values.forEach((resource, amount) -> {
            Objects.requireNonNull(resource, "resource");
            if (amount == null || amount < 0L) {
                throw new IllegalArgumentException(
                    "planned resource values must be non-negative");
            }
            if (amount > 0L) {
                ordered.put(resource, amount);
            }
        });
        return Collections.unmodifiableMap(ordered);
    }

    private static String shortHash(String sha256) {
        return sha256.substring("sha256:".length(), "sha256:".length() + 16);
    }

    public enum DecisionScope {
        BRANCH_LOCAL,
        AGGREGATE
    }

    public enum DecisionStatus {
        READY,
        BLOCKED
    }

    public enum BranchType {
        SEED_SEARCH,
        OBSERVATION,
        CONJECTURE_CANDIDATE,
        LIFECYCLE_CANDIDATE
    }

    public enum CandidateBranchStatus {
        ELIGIBLE_INCOMPLETE,
        COMPLETE,
        DUPLICATE,
        DISPROVED,
        INCONCLUSIVE
    }

    public enum ExecutionStatus {
        COMPLETED,
        ZERO_OUTPUT,
        BLOCKED
    }

    public record EvidenceBranch(
        String branchId,
        BranchType type,
        String familyId,
        String alphaFingerprint,
        String snapshotHash,
        String evidenceHash,
        boolean immutable
    ) {
        public EvidenceBranch {
            requireText(branchId, "branchId");
            type = Objects.requireNonNull(type, "type");
            requireText(familyId, "familyId");
            requireSha256(alphaFingerprint, "alphaFingerprint");
            requireSha256(snapshotHash, "snapshotHash");
            requireSha256(evidenceHash, "evidenceHash");
            if (!immutable) {
                throw new IllegalArgumentException(
                    "v2 evidence input branches must be immutable");
            }
        }

        String identityMaterial() {
            return branchId + '|' + type.name() + '|' + familyId + '|'
                + alphaFingerprint + '|' + snapshotHash + '|' + evidenceHash;
        }
    }

    public record AggregateDecision(
        String decisionId,
        String briefHash,
        EvidenceStage stage,
        DecisionScope scope,
        List<EvidenceBranch> inputBranches,
        int minimumInputCount,
        int minimumSupportDiversity,
        int minimumAlphaDiversity,
        BranchType outputBranchType,
        String outputNamespace,
        Map<ResourceKind, Long> plannedResources,
        DecisionStatus status,
        List<String> blockers,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public AggregateDecision {
            requireText(decisionId, "decisionId");
            requireSha256(briefHash, "briefHash");
            if (stage != EvidenceStage.CANDIDATE_FORMATION
                    || scope != DecisionScope.AGGREGATE) {
                throw new IllegalArgumentException(
                    "first v2 aggregate contract is candidate formation only");
            }
            inputBranches = orderedBranches(inputBranches);
            if (minimumInputCount < 2
                    || minimumSupportDiversity < 2
                    || minimumAlphaDiversity < 2) {
                throw new IllegalArgumentException(
                    "aggregate decision minima must be at least two");
            }
            if (outputBranchType != BranchType.CONJECTURE_CANDIDATE) {
                throw new IllegalArgumentException(
                    "candidate formation must output conjecture branches");
            }
            requireText(outputNamespace, "outputNamespace");
            plannedResources = immutableResources(plannedResources);
            status = Objects.requireNonNull(status, "status");
            blockers = blockers == null
                ? List.of()
                : blockers.stream().distinct().sorted().toList();
            if (status == DecisionStatus.READY && !blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "ready aggregate decision cannot contain blockers");
            }
            if (status == DecisionStatus.BLOCKED && blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "blocked aggregate decision requires blockers");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", PLAN_SCHEMA)
                .property("decisionId", decisionId)
                .property("briefHash", briefHash)
                .property("stage", stage.name())
                .property("scope", scope.name())
                .array("inputBranches", array -> inputBranches.forEach(branch ->
                    array.objectValue(object -> writeBranch(object, branch))))
                .property("minimumInputCount", minimumInputCount)
                .property("minimumSupportDiversity", minimumSupportDiversity)
                .property("minimumAlphaDiversity", minimumAlphaDiversity)
                .property("outputBranchType", outputBranchType.name())
                .property("outputNamespace", outputNamespace)
                .array("plannedResources", array -> plannedResources.entrySet()
                    .stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> array.objectValue(object -> object
                        .property("resource", entry.getKey().name())
                        .property("amount", entry.getValue()))))
                .property("status", status.name())
                .stringArray("blockers", blockers)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
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
                throw new IllegalArgumentException("unsupported v2 plan schema");
            }
            requireSha256(briefHash, "briefHash");
            decisions = decisions == null
                ? List.of()
                : decisions.stream()
                    .sorted(Comparator.comparing(AggregateDecision::decisionId))
                    .toList();
            if (plannerDecisionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "v2 planner decisions cannot be mathematical evidence");
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
                        .property("status", decision.status().name()))))
                .property("plannerDecisionIsMathematicalEvidence",
                    plannerDecisionIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record MiningCandidate(
        String candidateId,
        List<String> supportingObservationIds,
        String convergenceEvidenceHash
    ) {
        public MiningCandidate {
            requireText(candidateId, "candidateId");
            supportingObservationIds = supportingObservationIds == null
                ? List.of()
                : supportingObservationIds.stream().sorted().toList();
            requireSha256(convergenceEvidenceHash, "convergenceEvidenceHash");
        }

        String identityMaterial() {
            return candidateId + '|' + supportingObservationIds + '|'
                + convergenceEvidenceHash;
        }
    }

    public record RejectedCluster(
        String clusterId,
        String reason,
        String evidenceHash
    ) {
        public RejectedCluster {
            requireText(clusterId, "clusterId");
            requireText(reason, "reason");
            requireSha256(evidenceHash, "evidenceHash");
        }

        String identityMaterial() {
            return clusterId + '|' + reason + '|' + evidenceHash;
        }
    }

    public record MiningReport(
        String campaignId,
        String ruleInventoryHash,
        List<MiningCandidate> candidates,
        List<RejectedCluster> rejectedClusters,
        String reportHash
    ) {
        public MiningReport {
            requireText(campaignId, "campaignId");
            requireSha256(ruleInventoryHash, "ruleInventoryHash");
            candidates = candidates == null
                ? List.of()
                : candidates.stream()
                    .sorted(Comparator.comparing(MiningCandidate::candidateId))
                    .toList();
            rejectedClusters = rejectedClusters == null
                ? List.of()
                : rejectedClusters.stream()
                    .sorted(Comparator.comparing(RejectedCluster::clusterId))
                    .toList();
            requireUnique(
                candidates.stream().map(MiningCandidate::candidateId).toList(),
                "candidate IDs");
            requireUnique(
                rejectedClusters.stream().map(RejectedCluster::clusterId).toList(),
                "rejected cluster IDs");
            requireSha256(reportHash, "reportHash");
        }
    }

    public record OutputBranch(
        String branchId,
        BranchType type,
        String snapshotHash,
        String evidenceHash,
        CandidateBranchStatus status
    ) {
        public OutputBranch {
            requireText(branchId, "branchId");
            if (type != BranchType.CONJECTURE_CANDIDATE) {
                throw new IllegalArgumentException(
                    "formation output must be a conjecture branch");
            }
            requireSha256(snapshotHash, "snapshotHash");
            requireSha256(evidenceHash, "evidenceHash");
            status = Objects.requireNonNull(status, "status");
        }

        String identityMaterial() {
            return branchId + '|' + type.name() + '|' + snapshotHash + '|'
                + evidenceHash + '|' + status.name();
        }
    }

    public record SourceBranchRef(
        String branchId,
        String snapshotHash,
        String evidenceHash
    ) {
        public SourceBranchRef {
            requireText(branchId, "branchId");
            requireSha256(snapshotHash, "snapshotHash");
            requireSha256(evidenceHash, "evidenceHash");
        }

        String identityMaterial() {
            return branchId + '|' + snapshotHash + '|' + evidenceHash;
        }
    }

    public record BranchLineage(
        String schema,
        String outputBranchId,
        BranchType outputBranchType,
        String candidateId,
        String decisionHash,
        String miningReportHash,
        String convergenceEvidenceHash,
        List<SourceBranchRef> sourceBranches,
        String lineageHash
    ) {
        public BranchLineage {
            if (!LINEAGE_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported lineage schema");
            }
            requireText(outputBranchId, "outputBranchId");
            if (outputBranchType != BranchType.CONJECTURE_CANDIDATE) {
                throw new IllegalArgumentException(
                    "formation lineage must target conjecture branches");
            }
            requireText(candidateId, "candidateId");
            requireSha256(decisionHash, "decisionHash");
            requireSha256(miningReportHash, "miningReportHash");
            requireSha256(convergenceEvidenceHash, "convergenceEvidenceHash");
            sourceBranches = sourceBranches == null
                ? List.of()
                : sourceBranches.stream()
                    .sorted(Comparator.comparing(SourceBranchRef::branchId))
                    .toList();
            requireUnique(
                sourceBranches.stream().map(SourceBranchRef::branchId).toList(),
                "lineage source branch IDs");
            requireSha256(lineageHash, "lineageHash");
        }
    }

    public record CandidateRejection(
        String candidateId,
        String convergenceEvidenceHash,
        List<String> blockers
    ) {
        public CandidateRejection {
            requireText(candidateId, "candidateId");
            requireSha256(convergenceEvidenceHash, "convergenceEvidenceHash");
            blockers = blockers == null
                ? List.of()
                : blockers.stream().distinct().sorted().toList();
            if (blockers.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate rejection requires blockers");
            }
        }

        String identityMaterial() {
            return candidateId + '|' + convergenceEvidenceHash + '|' + blockers;
        }
    }

    public record AggregateExecutionReceipt(
        String schema,
        String decisionHash,
        String miningReportHash,
        String campaignId,
        String ruleInventoryHash,
        ExecutionStatus status,
        List<OutputBranch> outputBranches,
        List<BranchLineage> lineages,
        List<RejectedCluster> rejectedClusters,
        List<CandidateRejection> candidateRejections,
        boolean executionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public AggregateExecutionReceipt {
            if (!EXECUTION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported v2 execution schema");
            }
            requireSha256(decisionHash, "decisionHash");
            requireSha256(miningReportHash, "miningReportHash");
            requireText(campaignId, "campaignId");
            requireSha256(ruleInventoryHash, "ruleInventoryHash");
            status = Objects.requireNonNull(status, "status");
            outputBranches = outputBranches == null
                ? List.of()
                : outputBranches.stream()
                    .sorted(Comparator.comparing(OutputBranch::branchId))
                    .toList();
            lineages = lineages == null
                ? List.of()
                : lineages.stream()
                    .sorted(Comparator.comparing(BranchLineage::outputBranchId))
                    .toList();
            rejectedClusters = rejectedClusters == null
                ? List.of()
                : rejectedClusters.stream()
                    .sorted(Comparator.comparing(RejectedCluster::clusterId))
                    .toList();
            candidateRejections = candidateRejections == null
                ? List.of()
                : candidateRejections.stream()
                    .sorted(Comparator.comparing(CandidateRejection::candidateId))
                    .toList();
            if (outputBranches.size() != lineages.size()) {
                throw new IllegalArgumentException(
                    "every output branch requires exactly one lineage");
            }
            if (executionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "aggregate execution cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("decisionHash", decisionHash)
                .property("miningReportHash", miningReportHash)
                .property("campaignId", campaignId)
                .property("ruleInventoryHash", ruleInventoryHash)
                .property("status", status.name())
                .array("outputBranches", array -> outputBranches.forEach(branch ->
                    array.objectValue(object -> object
                        .property("branchId", branch.branchId())
                        .property("type", branch.type().name())
                        .property("snapshotHash", branch.snapshotHash())
                        .property("evidenceHash", branch.evidenceHash())
                        .property("status", branch.status().name()))))
                .array("lineages", array -> lineages.forEach(lineage ->
                    array.objectValue(object -> object
                        .property("schema", lineage.schema())
                        .property("outputBranchId", lineage.outputBranchId())
                        .property("candidateId", lineage.candidateId())
                        .property("decisionHash", lineage.decisionHash())
                        .property("miningReportHash", lineage.miningReportHash())
                        .property("convergenceEvidenceHash",
                            lineage.convergenceEvidenceHash())
                        .array("sourceBranches", sources -> lineage.sourceBranches()
                            .forEach(source -> sources.objectValue(item -> item
                                .property("branchId", source.branchId())
                                .property("snapshotHash", source.snapshotHash())
                                .property("evidenceHash", source.evidenceHash()))))
                        .property("lineageHash", lineage.lineageHash()))))
                .array("rejectedClusters", array -> rejectedClusters.forEach(cluster ->
                    array.objectValue(object -> object
                        .property("clusterId", cluster.clusterId())
                        .property("reason", cluster.reason())
                        .property("evidenceHash", cluster.evidenceHash()))))
                .array("candidateRejections", array -> candidateRejections.forEach(rejection ->
                    array.objectValue(object -> object
                        .property("candidateId", rejection.candidateId())
                        .property("convergenceEvidenceHash",
                            rejection.convergenceEvidenceHash())
                        .stringArray("blockers", rejection.blockers()))))
                .property("executionIsMathematicalEvidence",
                    executionIsMathematicalEvidence)
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
        String lineageDagHash,
        String nextPlanHash,
        boolean roundDecisionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public CampaignRound {
            if (!ROUND_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported v2 round schema");
            }
            requireSha256(planHash, "planHash");
            requireSha256(executionHash, "executionHash");
            requireSha256(lineageDagHash, "lineageDagHash");
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
                .property("lineageDagHash", lineageDagHash)
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

    private record CandidateValidation(
        List<EvidenceBranch> sources,
        List<String> blockers
    ) {
    }

    private static void writeBranch(JsonWriter json, EvidenceBranch branch) {
        json.property("branchId", branch.branchId())
            .property("type", branch.type().name())
            .property("familyId", branch.familyId())
            .property("alphaFingerprint", branch.alphaFingerprint())
            .property("snapshotHash", branch.snapshotHash())
            .property("evidenceHash", branch.evidenceHash())
            .property("immutable", branch.immutable());
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
