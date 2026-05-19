package de.regelsuche.mining;

import java.util.List;

public record RuleCandidate(
    String leftPattern,
    String rightPattern,
    int examplesCount,
    double averageScoreImprovement,
    int maximumScoreImprovement,
    boolean equivalenceVerified,
    boolean generalizationPlausible,
    boolean containsFreeParameters,
    List<String> parameterRelations,
    RuleStatus status,
    CandidateProofStatus proofStatus,
    String canonicalHash,
    List<String> supportingTransformationIds
) {
    public RuleCandidate {
        parameterRelations = List.copyOf(parameterRelations);
        supportingTransformationIds = supportingTransformationIds == null
            ? List.of()
            : List.copyOf(supportingTransformationIds);
    }

    public RuleCandidate(
        String leftPattern,
        String rightPattern,
        int examplesCount,
        double averageScoreImprovement,
        int maximumScoreImprovement,
        boolean equivalenceVerified,
        boolean generalizationPlausible,
        boolean containsFreeParameters,
        List<String> parameterRelations,
        RuleStatus status,
        CandidateProofStatus proofStatus,
        String canonicalHash
    ) {
        this(
            leftPattern,
            rightPattern,
            examplesCount,
            averageScoreImprovement,
            maximumScoreImprovement,
            equivalenceVerified,
            generalizationPlausible,
            containsFreeParameters,
            parameterRelations,
            status,
            proofStatus,
            canonicalHash,
            List.of()
        );
    }
}
