package de.regelsuche.search.moves;

/** Search-only policy. It cannot change a transformation, assumptions or proof strength. */
@FunctionalInterface
public interface MovePriorityPolicy {
    double score(SearchMove move, MoveState state, MoveContext context);
    MovePriorityPolicy INVENTORY_ORDER = (move, state, context) -> 0;
}
