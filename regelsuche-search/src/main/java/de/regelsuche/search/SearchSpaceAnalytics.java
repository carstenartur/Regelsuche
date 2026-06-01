package de.regelsuche.search;

public record SearchSpaceAnalytics(
        int statesExplored,
        int uniqueCanonicalStates,
        int convergentStates,
        int learnedMacroUsage,
        double averageBranchingFactor) {
}
