package de.regelsuche.experiments.autopilot;

import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.EvidenceStage;
import de.regelsuche.experiments.autopilot.AutonomousResearchBriefV2.ResourceKind;
import de.regelsuche.json.JsonWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Deterministic v2 fan-in/fan-out contracts for immutable observation evidence.
 */
public final class AutonomousEvidenceDagV2 {
    public static final String DAG_SCHEMA = "regelsuche.autonomous-evidence-dag/v2";
    public static final String DECISION_SCHEMA =
        "regelsuche.autonomous-aggregate-decision/v2";
    public static final String RECEIPT_SCHEMA =
        "regelsuche.autonomous-aggregate-receipt/v2";

    private AutonomousEvidenceDagV2() {
    }

    public static AggregateDecision planCandidateFormation(
        AutonomousResearchBriefV2 brief,
        String decisionId,
        List<ObservationBranch> suppliedInputs,
        Map<ResourceKind, Long> plannedResources,
        String reason
    ) {
        Objects.requireNonNull(brief, "brief");
        List<ObservationBranch> inputs = orderedInputs(suppliedInputs);
        validateInputBatch(brief, inputs);
        Map<ResourceKind, Long> resources = immutableResources(
            plannedResources, "plannedResources");
        validateCandidateFormationResources(brief, resources);
        String material = DECISION_SCHEMA
            + "\nbrief=" + brief.contentHash()
            + "\ndecisionId=" + decisionId
            + "\nstage=" + EvidenceStage.CANDIDATE_FORMATION.name()
            + "\nscope=" + StageScope.AGGREGATE.name()
            + "\nminimumInputs=" + brief.minimumAggregateInputs()
            + "\nminimumFamilies=" + brief.minimumDistinctFamilies()
            + "\nminimumEvidence=" + brief.minimumDistinctEvidence()
            + "\noutputNamespace=" + brief.outputNamespace()
            + "\ninputs=" + inputs.stream()
                .map(ObservationBranch::canonicalMaterial).toList()
            + "\nresources=" + resources
            + "\nreason=" + reason;
        return new AggregateDecision(
            DECISION_SCHEMA,
            decisionId,
            brief.contentHash(),
            EvidenceStage.CANDIDATE_FORMATION,
            StageScope.AGGREGATE,
            inputs,
            brief.minimumAggregateInputs(),
            brief.minimumDistinctFamilies(),
            brief.minimumDistinctEvidence(),
            brief.outputNamespace(),
            resources,
            reason,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            AutonomousResearchBrief.hash(material));
    }

    public static AggregateReceipt completeCandidateFormation(
        AggregateDecision decision,
        String miningEvidenceHash,
        List<CandidateDraft> suppliedCandidates,
        List<RejectedCluster> suppliedRejectedClusters,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources
    ) {
        Objects.requireNonNull(decision, "decision");
        requireSha256(miningEvidenceHash, "miningEvidenceHash");
        Map<ResourceKind, Long> executed = immutableResources(
            executedResources, "executedResources");
        Map<ResourceKind, Long> skipped = immutableResources(
            skippedResources, "skippedResources");
        validateResourceReceipt(decision, executed, skipped);
        if (executed.getOrDefault(ResourceKind.MINING_BATCHES, 0L) < 1L) {
            throw new IllegalArgumentException(
                "completed aggregate candidate formation requires one executed mining batch");
        }
        List<CandidateOutput> outputs = candidateOutputs(
            decision, miningEvidenceHash, suppliedCandidates);
        long candidateCapacity = decision.plannedResources()
            .getOrDefault(ResourceKind.CANDIDATES, 0L);
        if (outputs.size() > candidateCapacity) {
            throw new IllegalArgumentException(
                "aggregate receipt exceeds planned candidate capacity");
        }
        List<RejectedCluster> rejected = orderedRejectedClusters(
            decision, suppliedRejectedClusters);
        String contentHash = AutonomousResearchBrief.hash(receiptMaterial(
            decision,
            miningEvidenceHash,
            AggregateDisposition.COMPLETED,
            outputs,
            rejected,
            executed,
            skipped));
        return new AggregateReceipt(
            RECEIPT_SCHEMA,
            decision.contentHash(),
            miningEvidenceHash,
            AggregateDisposition.COMPLETED,
            outputs,
            rejected,
            executed,
            skipped,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static AggregateReceipt inconclusiveCandidateFormation(
        AggregateDecision decision,
        String miningEvidenceHash,
        String reasonCode,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources
    ) {
        Objects.requireNonNull(decision, "decision");
        requireSha256(miningEvidenceHash, "miningEvidenceHash");
        requireText(reasonCode, "reasonCode");
        Map<ResourceKind, Long> executed = immutableResources(
            executedResources, "executedResources");
        Map<ResourceKind, Long> skipped = immutableResources(
            skippedResources, "skippedResources");
        validateResourceReceipt(decision, executed, skipped);
        RejectedCluster reason = RejectedCluster.create(
            AutonomousResearchBrief.hash(decision.contentHash() + '|' + reasonCode),
            reasonCode,
            decision.inputs().stream().map(ObservationBranch::observationId).toList());
        String contentHash = AutonomousResearchBrief.hash(receiptMaterial(
            decision,
            miningEvidenceHash,
            AggregateDisposition.INCONCLUSIVE,
            List.of(),
            List.of(reason),
            executed,
            skipped));
        return new AggregateReceipt(
            RECEIPT_SCHEMA,
            decision.contentHash(),
            miningEvidenceHash,
            AggregateDisposition.INCONCLUSIVE,
            List.of(),
            List.of(reason),
            executed,
            skipped,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static AggregateReceipt backendUnavailable(
        AggregateDecision decision,
        String reasonCode
    ) {
        Objects.requireNonNull(decision, "decision");
        requireText(reasonCode, "reasonCode");
        RejectedCluster reason = RejectedCluster.create(
            AutonomousResearchBrief.hash(decision.contentHash() + '|' + reasonCode),
            reasonCode,
            decision.inputs().stream().map(ObservationBranch::observationId).toList());
        String unavailableHash = AutonomousResearchBrief.hash(
            decision.contentHash() + "|backend-unavailable");
        String contentHash = AutonomousResearchBrief.hash(receiptMaterial(
            decision,
            unavailableHash,
            AggregateDisposition.BACKEND_UNAVAILABLE,
            List.of(),
            List.of(reason),
            Map.of(),
            decision.plannedResources()));
        return new AggregateReceipt(
            RECEIPT_SCHEMA,
            decision.contentHash(),
            unavailableHash,
            AggregateDisposition.BACKEND_UNAVAILABLE,
            List.of(),
            List.of(reason),
            Map.of(),
            decision.plannedResources(),
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            contentHash);
    }

    public static EvidenceDagSnapshot snapshot(
        AutonomousResearchBriefV2 brief,
        List<ObservationBranch> suppliedObservations,
        List<AggregateDecision> suppliedDecisions,
        List<AggregateReceipt> suppliedReceipts
    ) {
        Objects.requireNonNull(brief, "brief");
        List<ObservationBranch> observations = orderedInputs(suppliedObservations);
        List<AggregateDecision> decisions = suppliedDecisions == null
            ? List.of()
            : suppliedDecisions.stream()
                .sorted(Comparator.comparing(AggregateDecision::decisionId))
                .toList();
        List<AggregateReceipt> receipts = suppliedReceipts == null
            ? List.of()
            : suppliedReceipts.stream()
                .sorted(Comparator.comparing(AggregateReceipt::decisionHash))
                .toList();
        validateSnapshot(brief, observations, decisions, receipts);
        StringBuilder material = new StringBuilder(DAG_SCHEMA)
            .append("\nbrief=").append(brief.contentHash());
        observations.forEach(item -> material.append("\nobservation=")
            .append(item.canonicalMaterial()));
        decisions.forEach(item -> material.append("\ndecision=")
            .append(item.contentHash()));
        receipts.forEach(item -> material.append("\nreceipt=")
            .append(item.contentHash()));
        return new EvidenceDagSnapshot(
            DAG_SCHEMA,
            brief.contentHash(),
            observations,
            decisions,
            receipts,
            false,
            "NOT_EVALUATED",
            "NOT_EVALUATED",
            AutonomousResearchBrief.hash(material.toString()));
    }

    private static List<CandidateOutput> candidateOutputs(
        AggregateDecision decision,
        String miningEvidenceHash,
        List<CandidateDraft> supplied
    ) {
        List<CandidateDraft> drafts = supplied == null
            ? List.of()
            : supplied.stream()
                .sorted(Comparator.comparing(CandidateDraft::conjectureId))
                .toList();
        if (drafts.stream().map(CandidateDraft::conjectureId).distinct().count()
                != drafts.size()) {
            throw new IllegalArgumentException("candidate conjecture IDs must be unique");
        }
        Map<String, ObservationBranch> byObservation = decision.inputs().stream()
            .collect(java.util.stream.Collectors.toMap(
                ObservationBranch::observationId,
                item -> item));
        List<CandidateOutput> outputs = new ArrayList<>();
        for (CandidateDraft draft : drafts) {
            List<SourceLink> sources = draft.supportingObservationIds().stream()
                .map(id -> {
                    ObservationBranch observation = byObservation.get(id);
                    if (observation == null) {
                        throw new IllegalArgumentException(
                            "candidate references an observation outside the aggregate decision: " + id);
                    }
                    return SourceLink.from(observation);
                })
                .sorted(Comparator.comparing(SourceLink::observationId))
                .toList();
            validateCandidateSupport(decision, sources);
            String outputBranchId = deterministicOutputBranchId(
                decision.outputNamespace(), draft.conjectureId());
            String lineageHash = AutonomousResearchBrief.hash(
                "regelsuche.candidate-lineage/v2"
                    + "\ndecision=" + decision.contentHash()
                    + "\nmining=" + miningEvidenceHash
                    + "\nconjecture=" + draft.conjectureId()
                    + "\nbranch=" + outputBranchId
                    + "\nevidence=" + draft.candidateEvidenceHash()
                    + "\nsources=" + sources.stream()
                        .map(SourceLink::canonicalMaterial).toList());
            outputs.add(new CandidateOutput(
                outputBranchId,
                BranchType.CONJECTURE_CANDIDATE,
                draft.conjectureId(),
                draft.candidateEvidenceHash(),
                sources,
                lineageHash));
        }
        if (outputs.stream().map(CandidateOutput::outputBranchId).distinct().count()
                != outputs.size()) {
            throw new IllegalArgumentException("deterministic output branch IDs must be unique");
        }
        return List.copyOf(outputs);
    }

    private static void validateCandidateSupport(
        AggregateDecision decision,
        List<SourceLink> sources
    ) {
        if (sources.size() < decision.minimumInputs()) {
            throw new IllegalArgumentException(
                "candidate support is below the aggregate input minimum");
        }
        if (sources.stream().map(SourceLink::observationId).distinct().count()
                != sources.size()) {
            throw new IllegalArgumentException(
                "candidate support observation IDs must be unique");
        }
        long families = sources.stream().map(SourceLink::familyId).distinct().count();
        long evidence = sources.stream().map(SourceLink::evidenceHash).distinct().count();
        if (families < decision.minimumDistinctFamilies()) {
            throw new IllegalArgumentException(
                "candidate support does not satisfy family diversity");
        }
        if (evidence < decision.minimumDistinctEvidence()) {
            throw new IllegalArgumentException(
                "candidate support does not satisfy evidence diversity");
        }
    }

    private static List<RejectedCluster> orderedRejectedClusters(
        AggregateDecision decision,
        List<RejectedCluster> supplied
    ) {
        List<RejectedCluster> rejected = supplied == null
            ? List.of()
            : supplied.stream()
                .sorted(Comparator.comparing(RejectedCluster::clusterHash))
                .toList();
        Set<String> allowedObservations = decision.inputs().stream()
            .map(ObservationBranch::observationId)
            .collect(java.util.stream.Collectors.toSet());
        for (RejectedCluster item : rejected) {
            if (!allowedObservations.containsAll(item.supportingObservationIds())) {
                throw new IllegalArgumentException(
                    "rejected cluster references evidence outside the aggregate decision");
            }
        }
        return List.copyOf(rejected);
    }

    private static void validateInputBatch(
        AutonomousResearchBriefV2 brief,
        List<ObservationBranch> inputs
    ) {
        if (inputs.size() < brief.minimumAggregateInputs()) {
            throw new IllegalArgumentException(
                "aggregate decision has insufficient observation inputs");
        }
        if (inputs.stream().map(ObservationBranch::branchId).distinct().count()
                != inputs.size()) {
            throw new IllegalArgumentException("observation branch IDs must be unique");
        }
        if (inputs.stream().map(ObservationBranch::observationId).distinct().count()
                != inputs.size()) {
            throw new IllegalArgumentException("observation IDs must be unique");
        }
        long families = inputs.stream().map(ObservationBranch::familyId).distinct().count();
        long evidence = inputs.stream().map(ObservationBranch::evidenceHash).distinct().count();
        if (families < brief.minimumDistinctFamilies()) {
            throw new IllegalArgumentException(
                "aggregate decision does not satisfy family diversity");
        }
        if (evidence < brief.minimumDistinctEvidence()) {
            throw new IllegalArgumentException(
                "aggregate decision does not satisfy evidence diversity");
        }
    }

    private static void validateCandidateFormationResources(
        AutonomousResearchBriefV2 brief,
        Map<ResourceKind, Long> resources
    ) {
        AutonomousResearchBriefV2.StageBudget configured = brief.budget(
            EvidenceStage.CANDIDATE_FORMATION);
        for (Map.Entry<ResourceKind, Long> entry : resources.entrySet()) {
            if (entry.getValue() > configured.configured(entry.getKey())) {
                throw new IllegalArgumentException(
                    "aggregate decision exceeds configured v2 budget: " + entry.getKey());
            }
        }
        if (resources.getOrDefault(ResourceKind.MINING_BATCHES, 0L) < 1L
                || resources.getOrDefault(ResourceKind.CANDIDATES, 0L) < 1L) {
            throw new IllegalArgumentException(
                "candidate formation requires mining-batch and candidate capacity");
        }
        Set<ResourceKind> allowed = Set.of(
            ResourceKind.WALL_CLOCK_MILLIS,
            ResourceKind.MINING_BATCHES,
            ResourceKind.CANDIDATES);
        if (!allowed.containsAll(resources.keySet())) {
            throw new IllegalArgumentException(
                "candidate formation received resources from another v2 stage");
        }
    }

    private static void validateResourceReceipt(
        AggregateDecision decision,
        Map<ResourceKind, Long> executed,
        Map<ResourceKind, Long> skipped
    ) {
        Set<ResourceKind> resources = new HashSet<>(executed.keySet());
        resources.addAll(skipped.keySet());
        if (!decision.plannedResources().keySet().containsAll(resources)) {
            throw new IllegalArgumentException(
                "aggregate receipt contains an unplanned resource");
        }
        for (ResourceKind resource : resources) {
            long consumed = Math.addExact(
                executed.getOrDefault(resource, 0L),
                skipped.getOrDefault(resource, 0L));
            if (consumed > decision.plannedResources().getOrDefault(resource, 0L)) {
                throw new IllegalArgumentException(
                    "aggregate receipt exceeds planned resource " + resource);
            }
        }
    }

    private static void validateSnapshot(
        AutonomousResearchBriefV2 brief,
        List<ObservationBranch> observations,
        List<AggregateDecision> decisions,
        List<AggregateReceipt> receipts
    ) {
        Map<String, AggregateDecision> byHash = new HashMap<>();
        for (AggregateDecision decision : decisions) {
            if (!brief.contentHash().equals(decision.briefHash())) {
                throw new IllegalArgumentException("DAG decision belongs to another brief");
            }
            if (byHash.putIfAbsent(decision.contentHash(), decision) != null) {
                throw new IllegalArgumentException("duplicate DAG decision hash");
            }
        }
        for (AggregateReceipt receipt : receipts) {
            if (!byHash.containsKey(receipt.decisionHash())) {
                throw new IllegalArgumentException("DAG receipt has no matching decision");
            }
        }
        Set<String> observationHashes = observations.stream()
            .map(ObservationBranch::contentHash)
            .collect(java.util.stream.Collectors.toSet());
        for (AggregateDecision decision : decisions) {
            if (!observationHashes.containsAll(decision.inputs().stream()
                    .map(ObservationBranch::contentHash).toList())) {
                throw new IllegalArgumentException(
                    "DAG decision input is not retained as an immutable observation branch");
            }
        }
    }

    private static List<ObservationBranch> orderedInputs(
        List<ObservationBranch> supplied
    ) {
        Objects.requireNonNull(supplied, "observationBranches");
        if (supplied.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("observation branches must not contain null");
        }
        return supplied.stream()
            .sorted(Comparator.comparing(ObservationBranch::branchId))
            .toList();
    }

    private static String receiptMaterial(
        AggregateDecision decision,
        String miningEvidenceHash,
        AggregateDisposition disposition,
        List<CandidateOutput> outputs,
        List<RejectedCluster> rejected,
        Map<ResourceKind, Long> executed,
        Map<ResourceKind, Long> skipped
    ) {
        return RECEIPT_SCHEMA
            + "\ndecision=" + decision.contentHash()
            + "\nmining=" + miningEvidenceHash
            + "\ndisposition=" + disposition.name()
            + "\noutputs=" + outputs.stream()
                .map(CandidateOutput::canonicalMaterial).toList()
            + "\nrejected=" + rejected.stream()
                .map(RejectedCluster::canonicalMaterial).toList()
            + "\nexecuted=" + executed
            + "\nskipped=" + skipped;
    }

    private static String deterministicOutputBranchId(
        String outputNamespace,
        String conjectureId
    ) {
        String hash = AutonomousResearchBrief.hash(
            "regelsuche.output-branch/v2|" + outputNamespace + '|' + conjectureId);
        return outputNamespace + "/candidate-" + hash.substring("sha256:".length(), 23);
    }

    private static Map<ResourceKind, Long> immutableResources(
        Map<ResourceKind, Long> supplied,
        String name
    ) {
        if (supplied == null || supplied.isEmpty()) {
            return Map.of();
        }
        EnumMap<ResourceKind, Long> ordered = new EnumMap<>(ResourceKind.class);
        supplied.forEach((resource, amount) -> {
            Objects.requireNonNull(resource, name + " resource");
            if (amount == null || amount < 0L) {
                throw new IllegalArgumentException(
                    name + " values must be non-negative");
            }
            if (amount > 0L) {
                ordered.put(resource, amount);
            }
        });
        return Collections.unmodifiableMap(ordered);
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

    public enum StageScope {
        BRANCH_LOCAL,
        AGGREGATE
    }

    public enum BranchType {
        SEED_SEARCH,
        OBSERVATION,
        CONJECTURE_CANDIDATE,
        LIFECYCLE_CANDIDATE
    }

    public enum AggregateDisposition {
        COMPLETED,
        INCONCLUSIVE,
        BACKEND_UNAVAILABLE
    }

    public record ObservationBranch(
        String branchId,
        BranchType branchType,
        String familyId,
        String observationId,
        String snapshotHash,
        String evidenceHash,
        String goalStatus,
        String contentHash
    ) {
        public ObservationBranch {
            requireText(branchId, "branchId");
            if (branchType != BranchType.OBSERVATION) {
                throw new IllegalArgumentException("observation branch must use OBSERVATION type");
            }
            requireText(familyId, "familyId");
            requireText(observationId, "observationId");
            requireSha256(snapshotHash, "snapshotHash");
            requireSha256(evidenceHash, "evidenceHash");
            if (!"UNTARGETED".equals(goalStatus)) {
                throw new IllegalArgumentException(
                    "v2 candidate formation accepts only UNTARGETED observations");
            }
            requireSha256(contentHash, "contentHash");
        }

        public static ObservationBranch create(
            String branchId,
            String familyId,
            String observationId,
            String snapshotHash,
            String evidenceHash
        ) {
            String material = "regelsuche.observation-branch/v2"
                + "\nbranch=" + branchId
                + "\nfamily=" + familyId
                + "\nobservation=" + observationId
                + "\nsnapshot=" + snapshotHash
                + "\nevidence=" + evidenceHash
                + "\ngoal=UNTARGETED";
            return new ObservationBranch(
                branchId,
                BranchType.OBSERVATION,
                familyId,
                observationId,
                snapshotHash,
                evidenceHash,
                "UNTARGETED",
                AutonomousResearchBrief.hash(material));
        }

        String canonicalMaterial() {
            return branchId + '|' + branchType.name() + '|' + familyId + '|'
                + observationId + '|' + snapshotHash + '|' + evidenceHash + '|'
                + goalStatus + '|' + contentHash;
        }
    }

    public record AggregateDecision(
        String schema,
        String decisionId,
        String briefHash,
        EvidenceStage stage,
        StageScope scope,
        List<ObservationBranch> inputs,
        int minimumInputs,
        int minimumDistinctFamilies,
        int minimumDistinctEvidence,
        String outputNamespace,
        Map<ResourceKind, Long> plannedResources,
        String reason,
        boolean decisionIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public AggregateDecision {
            if (!DECISION_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported aggregate decision schema");
            }
            requireText(decisionId, "decisionId");
            requireSha256(briefHash, "briefHash");
            if (stage != EvidenceStage.CANDIDATE_FORMATION
                    || scope != StageScope.AGGREGATE) {
                throw new IllegalArgumentException(
                    "v2 aggregate decision must be aggregate candidate formation");
            }
            inputs = orderedInputs(inputs);
            if (minimumInputs < 2
                    || minimumDistinctFamilies < 2
                    || minimumDistinctEvidence < 2) {
                throw new IllegalArgumentException("aggregate minima must be at least two");
            }
            requireText(outputNamespace, "outputNamespace");
            plannedResources = immutableResources(
                plannedResources, "plannedResources");
            requireText(reason, "reason");
            if (decisionIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "aggregate planner decision cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("decisionId", decisionId)
                .property("briefHash", briefHash)
                .property("stage", stage.name())
                .property("scope", scope.name())
                .property("minimumInputs", minimumInputs)
                .property("minimumDistinctFamilies", minimumDistinctFamilies)
                .property("minimumDistinctEvidence", minimumDistinctEvidence)
                .property("outputNamespace", outputNamespace)
                .array("inputs", array -> inputs.forEach(input ->
                    array.objectValue(object -> object
                        .property("branchId", input.branchId())
                        .property("familyId", input.familyId())
                        .property("observationId", input.observationId())
                        .property("snapshotHash", input.snapshotHash())
                        .property("evidenceHash", input.evidenceHash())
                        .property("goalStatus", input.goalStatus())
                        .property("contentHash", input.contentHash()))))
                .array("plannedResources", array -> plannedResources.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> array.objectValue(object -> object
                        .property("resource", entry.getKey().name())
                        .property("amount", entry.getValue()))))
                .property("reason", reason)
                .property("decisionIsMathematicalEvidence",
                    decisionIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record CandidateDraft(
        String conjectureId,
        String candidateEvidenceHash,
        List<String> supportingObservationIds
    ) {
        public CandidateDraft {
            requireText(conjectureId, "conjectureId");
            requireSha256(candidateEvidenceHash, "candidateEvidenceHash");
            supportingObservationIds = supportingObservationIds == null
                ? List.of()
                : supportingObservationIds.stream().distinct().sorted().toList();
            if (supportingObservationIds.isEmpty()) {
                throw new IllegalArgumentException(
                    "candidate draft requires supporting observation IDs");
            }
        }
    }

    public record SourceLink(
        String observationId,
        String sourceBranchId,
        String familyId,
        String snapshotHash,
        String evidenceHash,
        String observationBranchHash
    ) {
        public SourceLink {
            requireText(observationId, "observationId");
            requireText(sourceBranchId, "sourceBranchId");
            requireText(familyId, "familyId");
            requireSha256(snapshotHash, "snapshotHash");
            requireSha256(evidenceHash, "evidenceHash");
            requireSha256(observationBranchHash, "observationBranchHash");
        }

        static SourceLink from(ObservationBranch source) {
            return new SourceLink(
                source.observationId(),
                source.branchId(),
                source.familyId(),
                source.snapshotHash(),
                source.evidenceHash(),
                source.contentHash());
        }

        String canonicalMaterial() {
            return observationId + '|' + sourceBranchId + '|' + familyId + '|'
                + snapshotHash + '|' + evidenceHash + '|' + observationBranchHash;
        }
    }

    public record CandidateOutput(
        String outputBranchId,
        BranchType branchType,
        String conjectureId,
        String candidateEvidenceHash,
        List<SourceLink> sources,
        String lineageHash
    ) {
        public CandidateOutput {
            requireText(outputBranchId, "outputBranchId");
            if (branchType != BranchType.CONJECTURE_CANDIDATE) {
                throw new IllegalArgumentException(
                    "aggregate candidate output must use CONJECTURE_CANDIDATE type");
            }
            requireText(conjectureId, "conjectureId");
            requireSha256(candidateEvidenceHash, "candidateEvidenceHash");
            sources = sources == null
                ? List.of()
                : sources.stream()
                    .sorted(Comparator.comparing(SourceLink::observationId))
                    .toList();
            requireSha256(lineageHash, "lineageHash");
        }

        String canonicalMaterial() {
            return outputBranchId + '|' + branchType.name() + '|' + conjectureId + '|'
                + candidateEvidenceHash + '|'
                + sources.stream().map(SourceLink::canonicalMaterial).toList() + '|'
                + lineageHash;
        }
    }

    public record RejectedCluster(
        String clusterHash,
        String reasonCode,
        List<String> supportingObservationIds,
        String contentHash
    ) {
        public RejectedCluster {
            requireSha256(clusterHash, "clusterHash");
            requireText(reasonCode, "reasonCode");
            supportingObservationIds = supportingObservationIds == null
                ? List.of()
                : supportingObservationIds.stream().distinct().sorted().toList();
            requireSha256(contentHash, "contentHash");
        }

        public static RejectedCluster create(
            String clusterHash,
            String reasonCode,
            List<String> supportingObservationIds
        ) {
            List<String> support = supportingObservationIds == null
                ? List.of()
                : supportingObservationIds.stream().distinct().sorted().toList();
            return new RejectedCluster(
                clusterHash,
                reasonCode,
                support,
                AutonomousResearchBrief.hash(
                    "regelsuche.rejected-cluster/v2|" + clusterHash + '|'
                        + reasonCode + '|' + support));
        }

        String canonicalMaterial() {
            return clusterHash + '|' + reasonCode + '|'
                + supportingObservationIds + '|' + contentHash;
        }
    }

    public record AggregateReceipt(
        String schema,
        String decisionHash,
        String miningEvidenceHash,
        AggregateDisposition disposition,
        List<CandidateOutput> outputs,
        List<RejectedCluster> rejectedClusters,
        Map<ResourceKind, Long> executedResources,
        Map<ResourceKind, Long> skippedResources,
        boolean receiptIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public AggregateReceipt {
            if (!RECEIPT_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported aggregate receipt schema");
            }
            requireSha256(decisionHash, "decisionHash");
            requireSha256(miningEvidenceHash, "miningEvidenceHash");
            disposition = Objects.requireNonNull(disposition, "disposition");
            outputs = outputs == null
                ? List.of()
                : outputs.stream()
                    .sorted(Comparator.comparing(CandidateOutput::outputBranchId))
                    .toList();
            rejectedClusters = rejectedClusters == null
                ? List.of()
                : rejectedClusters.stream()
                    .sorted(Comparator.comparing(RejectedCluster::clusterHash))
                    .toList();
            executedResources = immutableResources(
                executedResources, "executedResources");
            skippedResources = immutableResources(
                skippedResources, "skippedResources");
            if (receiptIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "aggregate execution receipt cannot be mathematical evidence");
            }
            if (disposition != AggregateDisposition.COMPLETED && !outputs.isEmpty()) {
                throw new IllegalArgumentException(
                    "only completed mining batches may create candidate branches");
            }
            if (disposition == AggregateDisposition.BACKEND_UNAVAILABLE
                    && !executedResources.isEmpty()) {
                throw new IllegalArgumentException(
                    "unavailable aggregate backend cannot report executed work");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("decisionHash", decisionHash)
                .property("miningEvidenceHash", miningEvidenceHash)
                .property("disposition", disposition.name())
                .array("outputs", array -> outputs.forEach(output ->
                    array.objectValue(object -> object
                        .property("outputBranchId", output.outputBranchId())
                        .property("branchType", output.branchType().name())
                        .property("conjectureId", output.conjectureId())
                        .property("candidateEvidenceHash", output.candidateEvidenceHash())
                        .array("sources", sources -> output.sources().forEach(source ->
                            sources.objectValue(item -> item
                                .property("observationId", source.observationId())
                                .property("sourceBranchId", source.sourceBranchId())
                                .property("familyId", source.familyId())
                                .property("snapshotHash", source.snapshotHash())
                                .property("evidenceHash", source.evidenceHash())
                                .property("observationBranchHash",
                                    source.observationBranchHash()))))
                        .property("lineageHash", output.lineageHash()))))
                .array("rejectedClusters", array -> rejectedClusters.forEach(item ->
                    array.objectValue(object -> object
                        .property("clusterHash", item.clusterHash())
                        .property("reasonCode", item.reasonCode())
                        .stringArray("supportingObservationIds",
                            item.supportingObservationIds())
                        .property("contentHash", item.contentHash()))))
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
                .property("receiptIsMathematicalEvidence",
                    receiptIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    public record EvidenceDagSnapshot(
        String schema,
        String briefHash,
        List<ObservationBranch> observations,
        List<AggregateDecision> decisions,
        List<AggregateReceipt> receipts,
        boolean dagIsMathematicalEvidence,
        String promotionStatus,
        String publicEvidenceStatus,
        String contentHash
    ) {
        public EvidenceDagSnapshot {
            if (!DAG_SCHEMA.equals(schema)) {
                throw new IllegalArgumentException("unsupported evidence DAG schema");
            }
            requireSha256(briefHash, "briefHash");
            observations = observations == null
                ? List.of()
                : observations.stream()
                    .sorted(Comparator.comparing(ObservationBranch::branchId))
                    .toList();
            decisions = decisions == null
                ? List.of()
                : decisions.stream()
                    .sorted(Comparator.comparing(AggregateDecision::decisionId))
                    .toList();
            receipts = receipts == null
                ? List.of()
                : receipts.stream()
                    .sorted(Comparator.comparing(AggregateReceipt::decisionHash))
                    .toList();
            if (dagIsMathematicalEvidence) {
                throw new IllegalArgumentException(
                    "Autopilot evidence DAG cannot be mathematical evidence");
            }
            requireNotEvaluated(promotionStatus, "promotionStatus");
            requireNotEvaluated(publicEvidenceStatus, "publicEvidenceStatus");
            requireSha256(contentHash, "contentHash");
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("schema", schema)
                .property("briefHash", briefHash)
                .array("observations", array -> observations.forEach(item ->
                    array.objectValue(object -> object
                        .property("branchId", item.branchId())
                        .property("branchType", item.branchType().name())
                        .property("familyId", item.familyId())
                        .property("observationId", item.observationId())
                        .property("snapshotHash", item.snapshotHash())
                        .property("evidenceHash", item.evidenceHash())
                        .property("goalStatus", item.goalStatus())
                        .property("contentHash", item.contentHash()))))
                .array("decisions", array -> decisions.forEach(item ->
                    array.objectValue(object -> object
                        .property("decisionId", item.decisionId())
                        .property("contentHash", item.contentHash()))))
                .array("receipts", array -> receipts.forEach(item ->
                    array.objectValue(object -> object
                        .property("decisionHash", item.decisionHash())
                        .property("miningEvidenceHash", item.miningEvidenceHash())
                        .property("disposition", item.disposition().name())
                        .stringArray("outputBranchIds", item.outputs().stream()
                            .map(CandidateOutput::outputBranchId).toList())
                        .property("contentHash", item.contentHash()))))
                .property("dagIsMathematicalEvidence", dagIsMathematicalEvidence)
                .property("promotionStatus", promotionStatus)
                .property("publicEvidenceStatus", publicEvidenceStatus)
                .property("contentHash", contentHash)
                .endObject()
                .toString();
        }
    }

    private static void requireNotEvaluated(String value, String name) {
        if (!"NOT_EVALUATED".equals(value)) {
            throw new IllegalArgumentException(name + " must be NOT_EVALUATED");
        }
    }
}
