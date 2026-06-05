package de.regelsuche.moves.search;

/**
 * Configurable limits and toggles for bounded move search.
 *
 * <p>The hypothesis-specific flags are part of the public search contract even if
 * not all toggles are already consumed by the current realizer/search wiring.</p>
 */
public record MoveSearchOptions(
    int requestedMaxDepth,
    int hardMaxDepth,
    int maxStates,
    int maxGeneratedMovesPerNode,
    int maxHypothesesPerSource,
    int maxSubtreeComplexity,
    boolean enableTargetGuidedHypotheses,
    boolean enableSkeletonHypotheses,
    boolean enableComplexSubtreeAtoms
) {
    public static final int DEFAULT_REQUESTED_MAX_DEPTH = 8;
    public static final int DEFAULT_HARD_MAX_DEPTH = 12;
    public static final int DEFAULT_MAX_STATES = 240;
    public static final int DEFAULT_MAX_GENERATED_MOVES_PER_NODE = 80;
    public static final int DEFAULT_MAX_HYPOTHESES_PER_SOURCE = 24;
    public static final int DEFAULT_MAX_SUBTREE_COMPLEXITY = 32;

    public MoveSearchOptions {
        requestedMaxDepth = Math.max(1, requestedMaxDepth);
        hardMaxDepth = Math.max(1, hardMaxDepth);
        maxStates = Math.max(1, maxStates);
        maxGeneratedMovesPerNode = Math.max(1, maxGeneratedMovesPerNode);
        maxHypothesesPerSource = Math.max(1, maxHypothesesPerSource);
        maxSubtreeComplexity = Math.max(1, maxSubtreeComplexity);
    }

    public static MoveSearchOptions defaults() {
        return new MoveSearchOptions(
            DEFAULT_REQUESTED_MAX_DEPTH,
            DEFAULT_HARD_MAX_DEPTH,
            DEFAULT_MAX_STATES,
            DEFAULT_MAX_GENERATED_MOVES_PER_NODE,
            DEFAULT_MAX_HYPOTHESES_PER_SOURCE,
            DEFAULT_MAX_SUBTREE_COMPLEXITY,
            true,
            true,
            false
        );
    }

    public static MoveSearchOptions campaignSixConservative() {
        return new MoveSearchOptions(
            4,
            DEFAULT_HARD_MAX_DEPTH,
            120,
            40,
            12,
            16,
            true,
            true,
            false
        );
    }

    public int effectiveDepthLimit() {
        return Math.max(1, Math.min(requestedMaxDepth, hardMaxDepth));
    }
}
