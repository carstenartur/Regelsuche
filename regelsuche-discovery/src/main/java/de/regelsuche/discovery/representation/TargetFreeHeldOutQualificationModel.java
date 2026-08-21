package de.regelsuche.discovery.representation;

import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireSha256;
import static de.regelsuche.discovery.representation.RepresentationDiscoveryRunContractSupport.requireText;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.JSON;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.QUALIFICATION_DISCLOSED;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.QUALIFICATION_SCHEMA;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.QUALIFICATION_STATUS;
import static de.regelsuche.discovery.representation.TargetFreeHeldOutMatrixRunner.canonical;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

record RunArtifacts(
    PlanArtifact plan,
    FreezeArtifact freeze,
    QualificationArtifact qualification
) {
    public RunArtifacts {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(freeze, "freeze");
        Objects.requireNonNull(qualification, "qualification");
    }
}

record QualificationSpec(
    String schema,
    String evidenceStatus,
    List<CaseQualification> caseQualifications,
    String qualificationBoundary
) {
    public QualificationSpec {
        caseQualifications = List.copyOf(caseQualifications);
        qualificationBoundary = requireText(
            qualificationBoundary, "qualificationBoundary");
    }
}

record CaseQualification(
    String id,
    String expectedOutcome,
    List<String> forbiddenOutcomes,
    int minimumQualifiedDepth,
    int maximumQualifiedDepth,
    List<String> oracleWitnessRequiredRuleIds,
    List<String> referenceExpressions,
    boolean requireTemporaryComplexityIncrease,
    List<String> requiredAssumptions,
    List<String> requiredCapabilities
) {
    public CaseQualification {
        id = requireText(id, "qualification id");
        expectedOutcome = requireText(
            expectedOutcome, "expectedOutcome");
        forbiddenOutcomes = List.copyOf(new TreeSet<>(
            forbiddenOutcomes));
        oracleWitnessRequiredRuleIds = List.copyOf(
            oracleWitnessRequiredRuleIds);
        referenceExpressions = List.copyOf(new TreeSet<>(
            referenceExpressions));
        requiredAssumptions = List.copyOf(new TreeSet<>(
            requiredAssumptions));
        requiredCapabilities = List.copyOf(new TreeSet<>(
            requiredCapabilities));
    }
}

record CandidateQualification(
    String candidateHash,
    String expression,
    List<String> assumptions,
    int depth,
    List<String> pathRuleIds,
    boolean temporaryComplexityIncrease,
    String proofStatus,
    String oracleStatus,
    boolean referenceMatched,
    boolean depthQualified,
    boolean requiredAssumptionsPresent,
    boolean complexityQualified,
    List<String> newlyExposedStructures,
    List<String> unlockedCapabilities,
    List<String> executableCapabilities,
    List<String> forbiddenOutcomes,
    List<String> disqualificationReasons,
    boolean qualified,
    boolean negativeControlViolation
) {
    public CandidateQualification {
        candidateHash = requireSha256(
            candidateHash, "candidateHash");
        expression = requireText(expression, "expression");
        assumptions = List.copyOf(assumptions);
        pathRuleIds = List.copyOf(pathRuleIds);
        proofStatus = requireText(proofStatus, "proofStatus");
        oracleStatus = requireText(oracleStatus, "oracleStatus");
        newlyExposedStructures = List.copyOf(
            newlyExposedStructures);
        unlockedCapabilities = List.copyOf(unlockedCapabilities);
        executableCapabilities = List.copyOf(
            executableCapabilities);
        forbiddenOutcomes = List.copyOf(forbiddenOutcomes);
        disqualificationReasons = List.copyOf(
            disqualificationReasons);
        if (qualified && !disqualificationReasons.isEmpty()) {
            throw new IllegalArgumentException(
                "qualified candidates must have no reasons");
        }
    }
}

record QualificationRow(
    int sequence,
    String configurationId,
    String caseId,
    String policyId,
    int checkpoint,
    String freezeStatus,
    String candidateBatchHash,
    String candidateSetHash,
    String candidateFreezeReceiptHash,
    String workLedgerHash,
    String postFreezeDisclosureHash,
    String classificationCatalogHash,
    String classificationRuleInventoryHash,
    String expectedOutcome,
    String status,
    List<CandidateQualification> candidates,
    int qualifyingCandidateCount,
    int negativeControlViolationCount
) {
    public QualificationRow {
        if (sequence < 1 || checkpoint < 1) {
            throw new IllegalArgumentException(
                "invalid qualification row identity");
        }
        configurationId = requireSha256(
            configurationId, "configurationId");
        caseId = requireText(caseId, "caseId");
        policyId = requireText(policyId, "policyId");
        freezeStatus = requireText(
            freezeStatus, "freezeStatus");
        expectedOutcome = requireText(
            expectedOutcome, "expectedOutcome");
        status = requireText(status, "status");
        candidates = List.copyOf(candidates);
        int qualified = Math.toIntExact(candidates.stream()
            .filter(CandidateQualification::qualified).count());
        int violations = Math.toIntExact(candidates.stream()
            .filter(CandidateQualification::negativeControlViolation)
            .count());
        if (qualifyingCandidateCount != qualified
                || negativeControlViolationCount != violations) {
            throw new IllegalArgumentException(
                "qualification counts do not balance");
        }
        for (String hash : List.of(
                candidateBatchHash,
                candidateSetHash,
                candidateFreezeReceiptHash,
                workLedgerHash,
                postFreezeDisclosureHash,
                classificationCatalogHash,
                classificationRuleInventoryHash)) {
            requireSha256(hash, "qualification-row hash");
        }
    }
}

record PolicyOutcome(
    String policyId,
    String status,
    int qualifyingCandidateCount,
    int negativeControlViolationCount,
    int minimumQualifiedDepth
) {
    public PolicyOutcome {
        policyId = requireText(policyId, "policyId");
        status = requireText(status, "status");
        if (qualifyingCandidateCount < 0
                || negativeControlViolationCount < 0
                || minimumQualifiedDepth < 0) {
            throw new IllegalArgumentException(
                "policy outcome counters must not be negative");
        }
    }
}

record PolicyComparison(
    String caseId,
    int checkpoint,
    boolean eligible,
    List<PolicyOutcome> policies,
    List<String> leadingPolicyIds
) {
    public PolicyComparison {
        caseId = requireText(caseId, "caseId");
        policies = List.copyOf(policies);
        leadingPolicyIds = List.copyOf(leadingPolicyIds);
        if (checkpoint < 1 || policies.size() != 4) {
            throw new IllegalArgumentException(
                "policy comparison does not balance");
        }
    }
}

record QualificationSummary(
    int configuredRows,
    int qualifiedPositiveRows,
    int unqualifiedPositiveRows,
    int negativeControlPassedRows,
    int negativeControlFailedRows,
    int qualifiedCandidates,
    int negativeControlViolations,
    int eligibleMatchedWorkComparisons,
    int comparisonsWithLeader
) {
    static QualificationSummary derive(
        List<QualificationRow> rows,
        List<PolicyComparison> comparisons
    ) {
        return new QualificationSummary(
            rows.size(),
            countStatus(rows, "QUALIFIED"),
            countStatus(rows, "NO_QUALIFYING_CANDIDATE"),
            countStatus(rows, "NEGATIVE_CONTROL_PASSED"),
            countStatus(rows, "NEGATIVE_CONTROL_FAILED"),
            rows.stream().mapToInt(
                QualificationRow::qualifyingCandidateCount).sum(),
            rows.stream().mapToInt(
                QualificationRow::negativeControlViolationCount).sum(),
            Math.toIntExact(comparisons.stream()
                .filter(PolicyComparison::eligible).count()),
            Math.toIntExact(comparisons.stream()
                .filter(value -> !value.leadingPolicyIds().isEmpty())
                .count()));
    }

    private static int countStatus(
        List<QualificationRow> rows,
        String status
    ) {
        return Math.toIntExact(rows.stream()
            .filter(value -> status.equals(value.status())).count());
    }
}

record QualificationContent(
    String schema,
    String evidenceStatus,
    String repositoryRevision,
    String planHash,
    String candidateFreezeHash,
    String qualificationResource,
    String qualificationHash,
    long qualificationByteLength,
    String qualificationDisclosure,
    List<QualificationRow> rows,
    List<PolicyComparison> comparisons,
    QualificationSummary summary,
    String qualificationBoundary,
    String claimBoundary
) {
    public QualificationContent {
        if (!QUALIFICATION_SCHEMA.equals(schema)
                || !QUALIFICATION_STATUS.equals(evidenceStatus)
                || !repositoryRevision.matches("[0-9a-f]{40}")
                || qualificationByteLength < 1
                || !QUALIFICATION_DISCLOSED.equals(
                    qualificationDisclosure)) {
            throw new IllegalArgumentException(
                "qualification content is invalid");
        }
        for (String hash : List.of(
                planHash,
                candidateFreezeHash,
                qualificationHash)) {
            requireSha256(hash, "qualification content hash");
        }
        rows = List.copyOf(rows);
        comparisons = List.copyOf(comparisons);
        if (rows.size() != 144
                || comparisons.size() != 36
                || !summary.equals(QualificationSummary.derive(
                    rows, comparisons))) {
            throw new IllegalArgumentException(
                "qualification matrix does not balance");
        }
        qualificationBoundary = requireText(
            qualificationBoundary, "qualificationBoundary");
        claimBoundary = requireText(
            claimBoundary, "claimBoundary");
    }
}

record QualificationArtifact(
    QualificationContent content,
    String contentHash
) {
    public QualificationArtifact {
        Objects.requireNonNull(content, "content");
        contentHash = requireSha256(contentHash, "contentHash");
        if (!KnownStructureCatalog.sha256(canonical(content))
                .equals(contentHash)) {
            throw new IllegalArgumentException(
                "qualification content hash mismatch");
        }
    }

    static QualificationArtifact create(
        QualificationContent content
    ) {
        return new QualificationArtifact(
            content,
            KnownStructureCatalog.sha256(canonical(content)));
    }

    public String toCanonicalJson() {
        return canonical(this);
    }

    public static QualificationArtifact fromCanonicalJson(
        String source
    ) {
        try {
            QualificationArtifact artifact = JSON.readValue(
                Objects.requireNonNull(source, "source"),
                QualificationArtifact.class);
            if (!artifact.toCanonicalJson().equals(source)) {
                throw new IllegalArgumentException(
                    "qualification JSON is not canonical");
            }
            return artifact;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid qualification JSON", exception);
        }
    }
}
