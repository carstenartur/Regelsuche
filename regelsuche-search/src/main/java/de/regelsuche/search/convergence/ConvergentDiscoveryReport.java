package de.regelsuche.search.convergence;

import java.util.List;
import java.util.Set;

public record ConvergentDiscoveryReport(
    String inputExpression,
    String canonicalTargetExpression,
    List<ConvergentState> convergentStates,
    List<ConvergentPath> pathsToTarget,
    List<ConvergentState> sharedIntermediateStates,
    List<ConvergentPath> interestingAlternativePaths,
    Set<RuleFamily> ruleFamiliesUsed,
    Set<String> evidenceKinds
) {
    public ConvergentDiscoveryReport {
        convergentStates = List.copyOf(convergentStates);
        pathsToTarget = List.copyOf(pathsToTarget);
        sharedIntermediateStates = List.copyOf(sharedIntermediateStates);
        interestingAlternativePaths = List.copyOf(interestingAlternativePaths);
        ruleFamiliesUsed = Set.copyOf(ruleFamiliesUsed);
        evidenceKinds = Set.copyOf(evidenceKinds);
    }

    public boolean isGalleryEligible() {
        return pathsToTarget.size() >= 2 && ruleFamiliesUsed.stream()
            .filter(family -> family != RuleFamily.NORMALIZATION && family != RuleFamily.OTHER)
            .count() >= 2;
    }
}
