package de.regelsuche.api;

import de.regelsuche.mining.RuleCandidate;
import java.util.List;

public record RuleCandidateDto(
    String leftPattern,
    String rightPattern,
    int examplesCount,
    double averageScoreImprovement,
    int maximumScoreImprovement,
    boolean equivalenceVerified,
    boolean generalizationPlausible,
    boolean containsFreeParameters,
    List<String> parameterRelations,
    String status,
    String proofStatus,
    String canonicalHash,
    List<String> supportingTransformationIds
) {
    public static RuleCandidateDto from(RuleCandidate candidate) {
        return new RuleCandidateDto(
            candidate.leftPattern(),
            candidate.rightPattern(),
            candidate.examplesCount(),
            candidate.averageScoreImprovement(),
            candidate.maximumScoreImprovement(),
            candidate.equivalenceVerified(),
            candidate.generalizationPlausible(),
            candidate.containsFreeParameters(),
            candidate.parameterRelations(),
            candidate.status().name(),
            candidate.proofStatus().name(),
            candidate.canonicalHash(),
            candidate.supportingTransformationIds()
        );
    }
}
