package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.CANDIDATE_ORDER;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.EXACT_CHECKPOINT;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.FREEZE_SCHEMA;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.FREEZE_STATUS;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.JSON;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.NOT_COMPARABLE;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.QUALIFICATION_NOT_DISCLOSED;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonical;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.regelsuche.discovery.representation.RepresentationDiscoveryInformationBoundary.CandidateFreezeReceipt;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

record CandidateEvidence(
    String candidateHash,
    String expression,
    List<String> assumptions,
    int depth,
    List<String> pathExpressions,
    List<String> pathRuleIds,
    List<String> primitiveRuleIds,
    boolean equivalencePreserving,
    boolean temporaryComplexityIncrease
) {
    public CandidateEvidence {
        expression = requireText(expression, "expression");
        assumptions = List.copyOf(new TreeSet<>(assumptions));
        pathExpressions = List.copyOf(pathExpressions);
        pathRuleIds = List.copyOf(pathRuleIds);
        primitiveRuleIds = List.copyOf(primitiveRuleIds);
        if (depth < 1 || pathExpressions.size() < 2
                || pathRuleIds.isEmpty()
                || primitiveRuleIds.isEmpty()) {
            throw new IllegalArgumentException(
                "candidate lineage is incomplete");
        }
        candidateHash = requireSha256(
            candidateHash, "candidateHash");
        if (!candidateHash.equals(hashCandidate(
                expression,
                assumptions,
                depth,
                pathExpressions,
                pathRuleIds,
                primitiveRuleIds,
                equivalencePreserving,
                temporaryComplexityIncrease))) {
            throw new IllegalArgumentException(
                "candidate hash mismatch");
        }
    }

    static CandidateEvidence create(
        String expression,
        List<String> assumptions,
        int depth,
        List<String> pathExpressions,
        List<String> pathRuleIds,
        List<String> primitiveRuleIds,
        boolean equivalencePreserving,
        boolean temporaryComplexityIncrease
    ) {
        List<String> normalizedAssumptions = List.copyOf(
            new TreeSet<>(assumptions));
        return new CandidateEvidence(
            hashCandidate(
                expression,
                normalizedAssumptions,
                depth,
                pathExpressions,
                pathRuleIds,
                primitiveRuleIds,
                equivalencePreserving,
                temporaryComplexityIncrease),
            expression,
            normalizedAssumptions,
            depth,
            pathExpressions,
            pathRuleIds,
            primitiveRuleIds,
            equivalencePreserving,
            temporaryComplexityIncrease);
    }

    String proposalKey() {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, expression);
        KnownStructureCatalog.appendCanonicalList(
            descriptor, assumptions);
        return descriptor.toString();
    }

    private static String hashCandidate(
        String expression,
        List<String> assumptions,
        int depth,
        List<String> pathExpressions,
        List<String> pathRuleIds,
        List<String> primitiveRuleIds,
        boolean equivalencePreserving,
        boolean temporaryComplexityIncrease
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, FREEZE_SCHEMA + "/candidate");
        KnownStructureCatalog.appendCanonicalField(
            descriptor, requireText(expression, "expression"));
        KnownStructureCatalog.appendCanonicalList(
            descriptor, assumptions);
        KnownStructureCatalog.appendCanonicalField(
            descriptor, Integer.toString(depth));
        KnownStructureCatalog.appendCanonicalList(
            descriptor, pathExpressions);
        KnownStructureCatalog.appendCanonicalList(
            descriptor, pathRuleIds);
        KnownStructureCatalog.appendCanonicalList(
            descriptor, primitiveRuleIds);
        KnownStructureCatalog.appendCanonicalField(
            descriptor, Boolean.toString(equivalencePreserving));
        KnownStructureCatalog.appendCanonicalField(
            descriptor,
            Boolean.toString(temporaryComplexityIncrease));
        return KnownStructureCatalog.sha256(descriptor.toString());
    }
}

record WorkLedger(
    String schema,
    int checkpoint,
    int engineCalls,
    int materializedTransitions,
    int admittedTransitions,
    int admittedPrimitiveSteps,
    int exploredStates,
    int retainedStates,
    boolean exactCheckpointReached,
    String contentHash
) {
    public WorkLedger {
        if (!(FREEZE_SCHEMA + "/work-ledger/v1").equals(schema)
                || checkpoint < 1
                || engineCalls < 0
                || materializedTransitions < admittedTransitions
                || admittedTransitions < 0
                || admittedPrimitiveSteps < admittedTransitions
                || admittedPrimitiveSteps > checkpoint
                || exploredStates < 0
                || retainedStates < 1
                || exactCheckpointReached
                    != (admittedPrimitiveSteps == checkpoint)) {
            throw new IllegalArgumentException(
                "work ledger does not balance");
        }
        contentHash = requireSha256(contentHash, "contentHash");
        if (!contentHash.equals(hashWork(
                checkpoint,
                engineCalls,
                materializedTransitions,
                admittedTransitions,
                admittedPrimitiveSteps,
                exploredStates,
                retainedStates,
                exactCheckpointReached))) {
            throw new IllegalArgumentException(
                "work-ledger hash mismatch");
        }
    }

    static WorkLedger create(
        int checkpoint,
        int engineCalls,
        int materializedTransitions,
        int admittedTransitions,
        int admittedPrimitiveSteps,
        int exploredStates,
        int retainedStates
    ) {
        boolean exact = admittedPrimitiveSteps == checkpoint;
        return new WorkLedger(
            FREEZE_SCHEMA + "/work-ledger/v1",
            checkpoint,
            engineCalls,
            materializedTransitions,
            admittedTransitions,
            admittedPrimitiveSteps,
            exploredStates,
            retainedStates,
            exact,
            hashWork(
                checkpoint,
                engineCalls,
                materializedTransitions,
                admittedTransitions,
                admittedPrimitiveSteps,
                exploredStates,
                retainedStates,
                exact));
    }

    private static String hashWork(
        int checkpoint,
        int engineCalls,
        int materializedTransitions,
        int admittedTransitions,
        int admittedPrimitiveSteps,
        int exploredStates,
        int retainedStates,
        boolean exact
    ) {
        StringBuilder descriptor = new StringBuilder();
        KnownStructureCatalog.appendCanonicalField(
            descriptor, FREEZE_SCHEMA + "/work-ledger/v1");
        for (int value : List.of(
                checkpoint,
                engineCalls,
                materializedTransitions,
                admittedTransitions,
                admittedPrimitiveSteps,
                exploredStates,
                retainedStates)) {
            KnownStructureCatalog.appendCanonicalField(
                descriptor, Integer.toString(value));
        }
        KnownStructureCatalog.appendCanonicalField(
            descriptor, Boolean.toString(exact));
        return KnownStructureCatalog.sha256(descriptor.toString());
    }
}

record FreezeRow(
    int sequence,
    String configurationId,
    String caseId,
    String policyId,
    int checkpoint,
    String status,
    String terminalReason,
    List<String> limitReasons,
    long deterministicSeed,
    WorkBudget configuredBudget,
    String informationBoundaryHash,
    String formationSelectionCommitment,
    String formationRuleInventoryHash,
    String postFreezeCatalogCommitment,
    List<CandidateEvidence> candidates,
    int candidateLineageCount,
    int candidateSetCount,
    String candidateBatchHash,
    String candidateSetHash,
    String candidateFreezeReceiptHash,
    WorkLedger work
) {
    public FreezeRow {
        if (sequence < 1 || checkpoint < 1) {
            throw new IllegalArgumentException(
                "invalid freeze row identity");
        }
        configurationId = requireSha256(
            configurationId, "configurationId");
        caseId = requireText(caseId, "caseId");
        policyId = requireText(policyId, "policyId");
        status = requireText(status, "status");
        terminalReason = requireText(
            terminalReason, "terminalReason");
        limitReasons = List.copyOf(new TreeSet<>(limitReasons));
        candidates = candidates.stream()
            .sorted(CANDIDATE_ORDER).toList();
        if (candidateLineageCount != candidates.size()
                || candidateSetCount < 0
                || candidateSetCount > candidateLineageCount) {
            throw new IllegalArgumentException(
                "candidate counts do not balance");
        }
        for (String hash : List.of(
                informationBoundaryHash,
                formationSelectionCommitment,
                formationRuleInventoryHash,
                postFreezeCatalogCommitment,
                candidateBatchHash,
                candidateSetHash,
                candidateFreezeReceiptHash)) {
            requireSha256(hash, "freeze-row hash");
        }
        Objects.requireNonNull(configuredBudget, "configuredBudget");
        Objects.requireNonNull(work, "work");
        if (work.checkpoint() != checkpoint
                || EXACT_CHECKPOINT.equals(status)
                    != work.exactCheckpointReached()
                || !candidateBatchHash.equals(
                    KnownStructureCatalog.sha256(
                        canonical(candidates)))) {
            throw new IllegalArgumentException(
                "freeze row differs from its evidence");
        }
    }

    static FreezeRow create(
        PlanRow row,
        CaseSpec benchmarkCase,
        PolicySpec policy,
        RepresentationDiscoveryInformationBoundary boundary,
        ExecutionResult result,
        CandidateFreezeReceipt receipt,
        int candidateSetCount
    ) {
        String status = result.work().exactCheckpointReached()
            ? EXACT_CHECKPOINT : NOT_COMPARABLE;
        return new FreezeRow(
            row.sequence(),
            row.configurationId(),
            benchmarkCase.id(),
            policy.id(),
            row.checkpoint(),
            status,
            result.terminalReason(),
            result.limitReasons(),
            policy.deterministicSeed(),
            benchmarkCase.budget(),
            boundary.contentHash(),
            boundary.candidateFormationSelectionCommitment(),
            boundary.candidateFormationRuleInventoryHash(),
            boundary.postFreezeCatalogCommitment(),
            result.candidates(),
            result.candidates().size(),
            candidateSetCount,
            KnownStructureCatalog.sha256(
                canonical(result.candidates())),
            receipt.candidateSetHash(),
            receipt.contentHash(),
            result.work());
    }
}

record MatchedWorkGroup(
    String caseId,
    int checkpoint,
    boolean eligible,
    List<String> policyIds,
    List<String> workLedgerHashes
) {
    public MatchedWorkGroup {
        caseId = requireText(caseId, "caseId");
        if (checkpoint < 1) {
            throw new IllegalArgumentException(
                "checkpoint must be positive");
        }
        policyIds = List.copyOf(policyIds);
        workLedgerHashes = List.copyOf(workLedgerHashes);
        if (policyIds.size() != 4
                || workLedgerHashes.size() != 4) {
            throw new IllegalArgumentException(
                "matched-work group requires four policies");
        }
        workLedgerHashes.forEach(hash ->
            requireSha256(hash, "workLedgerHash"));
    }
}

record FreezeSummary(
    int configuredRows,
    int exactCheckpointRows,
    int nonComparableRows,
    int candidateLineages,
    int candidateSets,
    int matchedWorkGroups,
    int eligibleMatchedWorkGroups,
    int admittedPrimitiveSteps
) {
    static FreezeSummary derive(
        List<FreezeRow> rows,
        List<MatchedWorkGroup> groups
    ) {
        int exact = Math.toIntExact(rows.stream()
            .filter(row -> row.work().exactCheckpointReached()).count());
        return new FreezeSummary(
            rows.size(),
            exact,
            rows.size() - exact,
            rows.stream().mapToInt(
                FreezeRow::candidateLineageCount).sum(),
            rows.stream().mapToInt(
                FreezeRow::candidateSetCount).sum(),
            groups.size(),
            Math.toIntExact(groups.stream()
                .filter(MatchedWorkGroup::eligible).count()),
            rows.stream().mapToInt(row ->
                row.work().admittedPrimitiveSteps()).sum());
    }
}

record FreezeContent(
    String schema,
    String evidenceStatus,
    String repositoryRevision,
    String planHash,
    String preregistrationHash,
    String formationHash,
    String qualificationHash,
    long qualificationByteLength,
    String qualificationDisclosure,
    List<FreezeRow> rows,
    List<MatchedWorkGroup> matchedWorkGroups,
    FreezeSummary summary,
    String claimBoundary
) {
    public FreezeContent {
        if (!FREEZE_SCHEMA.equals(schema)
                || !FREEZE_STATUS.equals(evidenceStatus)
                || !repositoryRevision.matches("[0-9a-f]{40}")
                || qualificationByteLength < 1
                || !QUALIFICATION_NOT_DISCLOSED.equals(
                    qualificationDisclosure)) {
            throw new IllegalArgumentException(
                "candidate-freeze content is invalid");
        }
        for (String hash : List.of(
                planHash,
                preregistrationHash,
                formationHash,
                qualificationHash)) {
            requireSha256(hash, "freeze content hash");
        }
        rows = List.copyOf(rows);
        matchedWorkGroups = List.copyOf(matchedWorkGroups);
        if (rows.size() != 144
                || matchedWorkGroups.size() != 36
                || !summary.equals(FreezeSummary.derive(
                    rows, matchedWorkGroups))) {
            throw new IllegalArgumentException(
                "candidate-freeze matrix does not balance");
        }
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).sequence() != index + 1) {
                throw new IllegalArgumentException(
                    "freeze row sequence is not canonical");
            }
        }
        claimBoundary = requireText(
            claimBoundary, "claimBoundary");
    }
}

record FreezeArtifact(
    FreezeContent content,
    String contentHash
) {
    public FreezeArtifact {
        Objects.requireNonNull(content, "content");
        contentHash = requireSha256(contentHash, "contentHash");
        if (!KnownStructureCatalog.sha256(canonical(content))
                .equals(contentHash)) {
            throw new IllegalArgumentException(
                "candidate-freeze content hash mismatch");
        }
    }

    static FreezeArtifact create(FreezeContent content) {
        return new FreezeArtifact(
            content,
            KnownStructureCatalog.sha256(canonical(content)));
    }

    public String toCanonicalJson() {
        return canonical(this);
    }

    public static FreezeArtifact fromCanonicalJson(String source) {
        try {
            FreezeArtifact artifact = JSON.readValue(
                Objects.requireNonNull(source, "source"),
                FreezeArtifact.class);
            if (!artifact.toCanonicalJson().equals(source)) {
                throw new IllegalArgumentException(
                    "candidate-freeze JSON is not canonical");
            }
            return artifact;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid candidate-freeze JSON", exception);
        }
    }
}
