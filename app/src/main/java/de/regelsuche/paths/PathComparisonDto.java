package de.regelsuche.paths;

import de.regelsuche.mining.CandidateProofStatus;
import java.util.List;

/**
 * Result of {@link PathComparisonService#compare}. Returned by
 * {@code GET /api/paths/compare?left=...&right=...}.
 */
public record PathComparisonDto(
    String leftPathId,
    String rightPathId,
    List<String> sharedNodes,
    List<String> sharedRules,
    List<String> leftOnlySteps,
    List<String> rightOnlySteps,
    List<Integer> leftScoreSeries,
    List<Integer> rightScoreSeries,
    double leftTeachingScore,
    double rightTeachingScore,
    CandidateProofStatus leftProofStatus,
    CandidateProofStatus rightProofStatus,
    int leftAssumptionSteps,
    int rightAssumptionSteps,
    String shorterPath,
    String teachingPreferredPath,
    String fewerAssumptionsPath
) {
    public PathComparisonDto {
        sharedNodes = sharedNodes == null ? List.of() : List.copyOf(sharedNodes);
        sharedRules = sharedRules == null ? List.of() : List.copyOf(sharedRules);
        leftOnlySteps = leftOnlySteps == null ? List.of() : List.copyOf(leftOnlySteps);
        rightOnlySteps = rightOnlySteps == null ? List.of() : List.copyOf(rightOnlySteps);
        leftScoreSeries = leftScoreSeries == null ? List.of() : List.copyOf(leftScoreSeries);
        rightScoreSeries = rightScoreSeries == null ? List.of() : List.copyOf(rightScoreSeries);
        leftProofStatus = leftProofStatus == null ? CandidateProofStatus.OBSERVED : leftProofStatus;
        rightProofStatus = rightProofStatus == null ? CandidateProofStatus.OBSERVED : rightProofStatus;
        shorterPath = shorterPath == null ? "" : shorterPath;
        teachingPreferredPath = teachingPreferredPath == null ? "" : teachingPreferredPath;
        fewerAssumptionsPath = fewerAssumptionsPath == null ? "" : fewerAssumptionsPath;
    }
}
