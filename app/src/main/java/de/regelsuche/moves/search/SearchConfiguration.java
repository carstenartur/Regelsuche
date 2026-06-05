package de.regelsuche.moves.search;

/** Search configuration wrapper for move search options. */
public record SearchConfiguration(MoveSearchOptions moveSearchOptions) {
    public SearchConfiguration {
        moveSearchOptions = moveSearchOptions == null ? MoveSearchOptions.defaults() : moveSearchOptions;
    }

    public static SearchConfiguration defaults() {
        return new SearchConfiguration(MoveSearchOptions.defaults());
    }

    public static SearchConfiguration campaignSixConservative() {
        return new SearchConfiguration(MoveSearchOptions.campaignSixConservative());
    }

    public static SearchConfiguration fromLegacyBounds(int maxDepth, int maxStates) {
        MoveSearchOptions defaults = MoveSearchOptions.defaults();
        return new SearchConfiguration(new MoveSearchOptions(
            Math.max(1, maxDepth),
            defaults.hardMaxDepth(),
            Math.max(1, maxStates),
            defaults.maxGeneratedMovesPerNode(),
            defaults.maxHypothesesPerSource(),
            defaults.maxSubtreeComplexity(),
            defaults.enableTargetGuidedHypotheses(),
            defaults.enableSkeletonHypotheses(),
            defaults.enableComplexSubtreeAtoms()
        ));
    }
}
