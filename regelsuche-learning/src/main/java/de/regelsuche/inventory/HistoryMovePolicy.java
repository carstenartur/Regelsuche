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
    private final boolean typed;
    // A read-through source-feature cache, never a learning update. Recreate the policy for each run.
    private final java.util.Map<MoveState, StructuralMoveContext> contexts = new java.util.HashMap<>();
    public HistoryMovePolicy(RuleHistoryMemory.Snapshot history, Weights weights) { this(history, weights, false); }
    private HistoryMovePolicy(RuleHistoryMemory.Snapshot history, Weights weights, boolean typed) {
        this.history = java.util.Objects.requireNonNull(history);
        this.weights = java.util.Objects.requireNonNull(weights);
        this.typed = typed;
    }
    /** Fresh read-through cache for one typed run; the TRAIN snapshot is immutable. */
    public static TypedMoveSearch.TypedPolicy typed(RuleHistoryMemory.Snapshot history, Weights weights) {
        var policy = new HistoryMovePolicy(history, weights, true);
        return new TypedMoveSearch.TypedPolicy() {
            @Override public double score(SearchMove move, MoveState state, MoveContext context) {
                return policy.score(move, state, context);
            }
            @Override public double providerScore(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
                return policy.providerScore(provider, state, context);
            }
            @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
                return policy.stage(provider, state, context);
            }
            @Override public long contextWork(MoveState state, MoveContext context) {
                return policy.contextWork(state, context);
            }
        };
    }
    public static NativeMovePriorityPolicy nativePolicy(RuleHistoryMemory.Snapshot history,Weights weights) {
        var policy=new HistoryMovePolicy(history,weights,true);
        return new NativeMovePriorityPolicy() {
            private final java.util.Map<TypedMoveSearch.State,StructuralMoveContext> contexts=new java.util.HashMap<>();
            private StructuralMoveContext structure(TypedMoveSearch.State state){return contexts.computeIfAbsent(state,StructuralMoveContext::of);}
            private String key(TypedMoveSearch.State state){return structure(state).typedKey();}
            @Override public long contextWork(TypedMoveSearch.State state,TypedMoveSearch.Context context){return 2L*structure(state).visitedNodes();}
            @Override public double score(NativeSearchMove move,TypedMoveSearch.State state,TypedMoveSearch.Context context){
                return policy.rank(policy.features(key(state),state.previousRule(),move.ruleFamily(),move.ruleId(),
                    move.descriptor().valueEvidence(),move.descriptor().proofStrength(),move.capabilityDelta().size(),
                    move.targetExpression().equals(context.goal()),move.generationCost(),move.executionWork().canonicalWorkUnits()));
            }
            @Override public double providerScore(MoveProvider.Descriptor provider,TypedMoveSearch.State state,TypedMoveSearch.Context context){
                return policy.providerScore(provider,key(state),state.previousRule());
            }
            @Override public MovePriorityPolicy.Stage stage(MoveProvider.Descriptor provider,TypedMoveSearch.State state,TypedMoveSearch.Context context){
                return policy.stage(provider,key(state),state.previousRule(),NativeMovePriorityPolicy.super.stage(provider,state,context));
            }
        };
    }
    public RuleHistoryMemory.Snapshot history() { return history; }
    public Weights weights() { return weights; }
    private StructuralMoveContext context(MoveState state) {
        return contexts.computeIfAbsent(state, typed ? StructuralMoveContext::fromTyped : StructuralMoveContext::of);
    }
    private String contextKey(MoveState state) { return typed ? context(state).typedKey() : context(state).key(); }
    @Override public long contextWork(MoveState state, MoveContext context) { return 2L * context(state).visitedNodes(); }
    public Features features(SearchMove move, MoveState state, MoveContext context) {
        return features(contextKey(state),state.previousRule(),move.ruleFamily(),move.ruleId(),move.valueEvidence(),
            move.proofStrength(),move.capabilityDelta().size(),move.transformation().transformedExpression().equals(context.goal()),
            move.generationCost(),move.applicationCost());
    }
    private Features features(String key,String previous,String ruleFamily,String ruleId,SearchMove.ValueEvidence value,
            SearchMove.ProofStrength proof,int capabilities,boolean goal,long generation,long application) {
        var family=history.family(key,ruleFamily);var continuation=history.continuation(key,previous,ruleId);
        double verification=family.applications()==0?application:(double)family.verificationWork()/family.applications();
        return new Features(value.knownDepthCompression()*value.confidence(),
            family.successValue()+continuation.successValue()+Math.log1p(family.averageWorkSaved()),capabilities,goal?1:0,
            proof==SearchMove.ProofStrength.VERIFIED?2:proof==SearchMove.ProofStrength.REPLAYABLE?1:0,
            Math.log1p(generation)+family.duplicateRate(),family.failureRate(),verification);
    }
    @Override public double score(SearchMove move, MoveState state, MoveContext context) {
        return rank(features(move,state,context));
    }
    private double rank(Features f) {
        return weights.compression() * f.compression() + weights.history() * f.history() + weights.capability() * f.capability()
            + weights.goal() * f.goal() + weights.proof() * f.proof() - weights.branching() * f.branching()
            - weights.failure() * f.failure() - weights.verification() * f.verification();
    }
    @Override public double providerScore(MoveProvider.Descriptor provider, MoveState state, MoveContext ignored) {
        return providerScore(provider,contextKey(state),state.previousRule());
    }
    private double providerScore(MoveProvider.Descriptor provider,String key,String previousRule) {
        var family=history.family(key,provider.ruleFamily());
        return weights.compression() * provider.valueEvidence().knownDepthCompression() * provider.valueEvidence().confidence()
            + weights.history() * (family.successValue() + history.continuation(key, previousRule, provider.id()).successValue())
            - weights.branching() * family.duplicateRate() - weights.failure() * family.failureRate();
    }
    @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
        // Expensive/bridge lanes do not become cheap simply because they were useful before.
        return stage(provider,contextKey(state),state.previousRule(),MovePriorityPolicy.super.stage(provider,state,context));
    }
    private Stage stage(MoveProvider.Descriptor provider,String key,String previousRule,Stage ordinary) {
        if (ordinary == Stage.EXPENSIVE || ordinary == Stage.EXPLORATION) return ordinary;
        var continuation = history.continuation(key,previousRule,provider.id());
        return continuation.success() >= 2 && continuation.failure() == 0 ? Stage.PRINCIPAL_HISTORY : ordinary;
    }
}
