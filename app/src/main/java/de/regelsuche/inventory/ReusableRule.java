package de.regelsuche.inventory;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import java.time.Instant;
import java.util.List;

public record ReusableRule(
    String id,
    String leftPattern,
    String rightPattern,
    List<String> parameterRelations,
    CandidateProofStatus proofStatus,
    RuleStatus knownRuleStatus,
    int supportingExamples,
    double averageImprovement,
    Instant createdAt,
    String canonicalHash,
    Instant lastUsedAt,
    int usageCount,
    int occurrenceCount,
    List<String> supportingPathIds,
    double confidenceScore,
    List<String> assumptions
) {
    public ReusableRule {
        if (id == null || id.isBlank() || leftPattern == null || rightPattern == null) {
            throw new IllegalArgumentException("id and patterns are required");
        }
        parameterRelations = List.copyOf(parameterRelations);
        proofStatus = proofStatus == null ? CandidateProofStatus.OBSERVED : proofStatus;
        knownRuleStatus = knownRuleStatus == null ? RuleStatus.NEW : knownRuleStatus;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        canonicalHash = canonicalHash == null ? "" : canonicalHash;
        if (usageCount < 0) {
            throw new IllegalArgumentException("usageCount must not be negative");
        }
        if (occurrenceCount < 0) {
            throw new IllegalArgumentException("occurrenceCount must not be negative");
        }
        supportingPathIds = supportingPathIds == null ? List.of() : List.copyOf(supportingPathIds);
        assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
        if (confidenceScore < 0.0 || confidenceScore > 1.0) {
            // Clamp instead of throwing so adapters loading legacy data don't blow up.
            confidenceScore = Math.max(0.0, Math.min(1.0, confidenceScore));
        }
    }

    public ReusableRule(
        String id,
        String leftPattern,
        String rightPattern,
        List<String> parameterRelations,
        CandidateProofStatus proofStatus,
        RuleStatus knownRuleStatus,
        int supportingExamples,
        double averageImprovement,
        Instant createdAt,
        String canonicalHash,
        Instant lastUsedAt,
        int usageCount,
        int occurrenceCount,
        List<String> supportingPathIds,
        double confidenceScore
    ) {
        this(
            id, leftPattern, rightPattern, parameterRelations, proofStatus, knownRuleStatus,
            supportingExamples, averageImprovement, createdAt, canonicalHash, lastUsedAt,
            usageCount, occurrenceCount, supportingPathIds, confidenceScore, List.of()
        );
    }

    /** Backwards-compatible 9-argument constructor used by older callers/tests. */
    public ReusableRule(
        String id,
        String leftPattern,
        String rightPattern,
        List<String> parameterRelations,
        CandidateProofStatus proofStatus,
        RuleStatus knownRuleStatus,
        int supportingExamples,
        double averageImprovement,
        Instant createdAt
    ) {
        this(
            id,
            leftPattern,
            rightPattern,
            parameterRelations,
            proofStatus,
            knownRuleStatus,
            supportingExamples,
            averageImprovement,
            createdAt,
            "",
            null,
            0,
            0,
            List.of(),
            0.0,
            List.of()
        );
    }

    /** Backwards-compatible 12-argument constructor used by older callers/tests. */
    public ReusableRule(
        String id,
        String leftPattern,
        String rightPattern,
        List<String> parameterRelations,
        CandidateProofStatus proofStatus,
        RuleStatus knownRuleStatus,
        int supportingExamples,
        double averageImprovement,
        Instant createdAt,
        String canonicalHash,
        Instant lastUsedAt,
        int usageCount
    ) {
        this(
            id,
            leftPattern,
            rightPattern,
            parameterRelations,
            proofStatus,
            knownRuleStatus,
            supportingExamples,
            averageImprovement,
            createdAt,
            canonicalHash,
            lastUsedAt,
            usageCount,
            0,
            List.of(),
            0.0,
            List.of()
        );
    }

    public ReusableRule withUsage(Instant lastUsedAt, int usageCount) {
        return new ReusableRule(
            id,
            leftPattern,
            rightPattern,
            parameterRelations,
            proofStatus,
            knownRuleStatus,
            supportingExamples,
            averageImprovement,
            createdAt,
            canonicalHash,
            lastUsedAt,
            usageCount,
            occurrenceCount,
            supportingPathIds,
            confidenceScore,
            assumptions
        );
    }

    /** Returns a copy reflecting one additional successful occurrence in a path. */
    public ReusableRule withLearningProgress(
        int newOccurrenceCount,
        double newAverageImprovement,
        List<String> mergedSupportingPathIds,
        double newConfidenceScore
    ) {
        return new ReusableRule(
            id,
            leftPattern,
            rightPattern,
            parameterRelations,
            proofStatus,
            knownRuleStatus,
            supportingExamples,
            newAverageImprovement,
            createdAt,
            canonicalHash,
            lastUsedAt,
            usageCount,
            newOccurrenceCount,
            mergedSupportingPathIds,
            newConfidenceScore,
            assumptions
        );
    }

    public ReusableRule withAssumptions(List<String> newAssumptions) {
        return new ReusableRule(
            id, leftPattern, rightPattern, parameterRelations, proofStatus, knownRuleStatus,
            supportingExamples, averageImprovement, createdAt, canonicalHash, lastUsedAt,
            usageCount, occurrenceCount, supportingPathIds, confidenceScore, newAssumptions
        );
    }

    public String assumptionFingerprint() {
        return AssumptionSignature.ofExpressions(assumptions).fingerprint();
    }
}
