package de.regelsuche.inventory;

import de.regelsuche.search.moves.*;

/** Frozen activity changes order only. Even COLD and SHADOW providers remain enumerable. */
public record ActivityMovePolicy(RuleActivityMemory.Snapshot activity, MovePriorityPolicy delegate) implements MovePriorityPolicy {
    @Override public long contextWork(MoveState state, MoveContext context) { return delegate.contextWork(state, context); }
    @Override public double score(SearchMove move, MoveState state, MoveContext context) {
        return delegate.score(move, state, context) + bonus(move.ruleId());
    }
    @Override public double providerScore(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
        return delegate.providerScore(provider, state, context) + bonus(provider.id());
    }
    @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
        if (provider.sourceKind() != SearchMove.SourceKind.LEARNED) return delegate.stage(provider, state, context);
        return switch (activity.tier(provider.id())) {
            case HOT, WARM -> delegate.stage(provider, state, context);
            case COLD, SHADOW -> Stage.EXPLORATION;
        };
    }
    private double bonus(String id) {
        var entry = activity.rules().get(id);
        return entry == null ? 0 : Math.max(-10, Math.min(10, entry.activity()));
    }
}
