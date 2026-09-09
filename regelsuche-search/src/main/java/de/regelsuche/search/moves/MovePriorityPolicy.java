package de.regelsuche.search.moves;

/** Search-only policy. It cannot change a transformation, assumptions or proof strength. */
@FunctionalInterface
public interface MovePriorityPolicy {
    double score(SearchMove move, MoveState state, MoveContext context);
    enum Stage { PRINCIPAL_HISTORY, FORCED_CHEAP, VALUABLE_LEARNED, NORMAL_PRIMITIVE, EXPLORATION, EXPENSIVE }
    default Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
        return switch (provider.sourceKind()) {
            case PREPARATION, SOLVER -> Stage.EXPENSIVE;
            case BRIDGE, HYPOTHESIS -> Stage.EXPLORATION;
            case LEARNED -> provider.valueEvidence().confidence() >= 0.75
                && provider.valueEvidence().knownDepthCompression() > 0 ? Stage.VALUABLE_LEARNED : Stage.EXPLORATION;
            case PRIMITIVE, EXPERT -> Stage.NORMAL_PRIMITIVE;
        };
    }
    default double providerScore(MoveProvider.Descriptor provider, MoveState state, MoveContext context) { return 0; }
    MovePriorityPolicy INVENTORY_ORDER = (move, state, context) -> 0;
}
