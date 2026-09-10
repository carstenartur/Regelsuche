package de.regelsuche.inventory;

import de.regelsuche.search.moves.*;

/** An inspectable linear policy with fixed weights and immutable TRAIN tables. */
public final class HistoryMovePolicy implements MovePriorityPolicy {
    public record Weights(double compression, double history, double capability, double goal, double proof,
            double branching, double failure, double verification) {
        public static final Weights DEFAULT = new Weights(3, 4, 6, 8, 0.25, 1, 3, 0.02);
        public Weights {
            for (double weight : new double[]{compression, history, capability, goal, proof, branching, failure, verification})
                if (!Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("invalid ranking weight");
        }
    }
    public record Features(double compression, double history, double capability, double goal, double proof,
            double branching, double failure, double verification) {}
    private final RuleHistoryMemory.Snapshot history;
    private final Weights weights;
    // A read-through source-feature cache, never a learning update. Recreate the policy for each run.
    private final java.util.Map<MoveState, StructuralMoveContext> contexts = new java.util.HashMap<>();
    public HistoryMovePolicy(RuleHistoryMemory.Snapshot history, Weights weights) { this.history = history; this.weights = weights; }
    public RuleHistoryMemory.Snapshot history() { return history; }
    public Weights weights() { return weights; }
    private StructuralMoveContext context(MoveState state) { return contexts.computeIfAbsent(state, StructuralMoveContext::of); }
    @Override public long contextWork(MoveState state, MoveContext context) { return 2L * context(state).visitedNodes(); }
    public Features features(SearchMove move, MoveState state, MoveContext context) {
        String key = context(state).key(); var family = history.family(key, move.ruleFamily());
        var continuation = history.continuation(key, state.previousRule(), move.ruleId());
        double verification = family.applications() == 0 ? move.applicationCost() : (double) family.verificationWork() / family.applications();
        return new Features(move.valueEvidence().knownDepthCompression() * move.valueEvidence().confidence(),
            family.successValue() + continuation.successValue() + Math.log1p(family.averageWorkSaved()), move.capabilityDelta().size(),
            move.transformation().transformedExpression().equals(context.goal()) ? 1 : 0,
            move.proofStrength() == SearchMove.ProofStrength.VERIFIED ? 2 : move.proofStrength() == SearchMove.ProofStrength.REPLAYABLE ? 1 : 0,
            Math.log1p(move.generationCost()) + family.duplicateRate(), family.failureRate(), verification);
    }
    @Override public double score(SearchMove move, MoveState state, MoveContext context) {
        var f = features(move, state, context);
        return weights.compression() * f.compression() + weights.history() * f.history() + weights.capability() * f.capability()
            + weights.goal() * f.goal() + weights.proof() * f.proof() - weights.branching() * f.branching()
            - weights.failure() * f.failure() - weights.verification() * f.verification();
    }
    @Override public double providerScore(MoveProvider.Descriptor provider, MoveState state, MoveContext ignored) {
        String key = context(state).key(); var family = history.family(key, provider.ruleFamily());
        return weights.compression() * provider.valueEvidence().knownDepthCompression() * provider.valueEvidence().confidence()
            + weights.history() * (family.successValue() + history.continuation(key, state.previousRule(), provider.id()).successValue())
            - weights.branching() * family.duplicateRate() - weights.failure() * family.failureRate();
    }
    @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
        // Expensive/bridge lanes do not become cheap simply because they were useful before.
        var ordinary = MovePriorityPolicy.super.stage(provider, state, context);
        if (ordinary == Stage.EXPENSIVE || ordinary == Stage.EXPLORATION) return ordinary;
        var continuation = history.continuation(context(state).key(), state.previousRule(), provider.id());
        return continuation.success() >= 2 && continuation.failure() == 0 ? Stage.PRINCIPAL_HISTORY : ordinary;
    }
}
