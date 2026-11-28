package de.regelsuche.evolution;

import static de.regelsuche.evolution.CheckedSchemaSupport.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.search.moves.NativeNodeCountObjective;
import de.regelsuche.search.moves.TypedMoveSearch;
import de.regelsuche.search.moves.TypedSourceOnlySearch;
import java.util.IdentityHashMap;

/** The checked domain walk already counts occurrences. Share that count only within one task. */
final class CheckedDomainSizeReceipts implements RetainedGraph.View {
    private static final int CAPACITY = 64;
    private final CheckedLearnedSchemaModel.Bounds bounds;
    private final IdentityHashMap<Expr, Integer> sizes = new IdentityHashMap<>();

    private final LearnedTacticPolicy.Dispatch tactics;
    private final String goal;
    private final java.util.Set<String> disabledSchemas;

    CheckedDomainSizeReceipts(CheckedLearnedSchemaModel.Bounds bounds) { this(bounds,null,null); }
    CheckedDomainSizeReceipts(CheckedLearnedSchemaModel.Bounds bounds, LearnedTacticPolicy.Dispatch tactics, String goal) {
        this(bounds, tactics, goal, null);
    }
    CheckedDomainSizeReceipts(CheckedLearnedSchemaModel.Bounds bounds, LearnedTacticPolicy.Dispatch tactics, String goal,
            java.util.Set<String> disabledSchemas) {
        this.disabledSchemas = disabledSchemas;
        this.bounds = bounds;
        this.tactics = tactics;
        this.goal = goal;
    }
    CheckedSchemaMatcherPlan choose(Expr state, CheckedSchemaMatcherPlan defaultPlan, Work work) {
        return tactics == null ? defaultPlan : tactics.choose(state,goal,defaultPlan,work);
    }

    boolean disabled(String schemaId, de.regelsuche.search.moves.IncrementalProviderContract.Meter meter) {
        if (tactics == null && disabledSchemas == null) return false;
        meter.charge(de.regelsuche.search.moves.IncrementalProviderContract.Operation.MATCH,1);
        return (disabledSchemas == null ? tactics.disabled() : disabledSchemas).contains(schemaId);
    }

    synchronized TypedSourceOnlySearch.Score evaluate(TypedMoveSearch.State state) {
        if (RetainedOperation.isObserved()) return NativeNodeCountObjective.INSTANCE.evaluate(state);
        var work = new Work();
        Integer known = size(state.expression(), work);
        if (known != null) return new TypedSourceOnlySearch.Score(known, work.units);
        try {
            int count = countedDomain(state.expression(), bounds, work);
            remember(state.expression(), count, work);
            return new TypedSourceOnlySearch.Score(count, work.units);
        } catch (DomainRejected unsupported) {
            var fallback = NativeNodeCountObjective.INSTANCE.evaluate(state);
            return new TypedSourceOnlySearch.Score(fallback.value(), Math.addExact(work.units, fallback.work()));
        }
    }

    /** A miss uses the original objective across its entire grammar, without issuing a domain receipt. */
    TypedSourceOnlySearch.Score scoreGoal(TypedMoveSearch.State state) {
        if (RetainedOperation.isObserved()) return NativeNodeCountObjective.INSTANCE.evaluate(state);
        var work = new Work();
        Integer known = size(state.expression(), work);
        if (known != null) return new TypedSourceOnlySearch.Score(known, work.units);
        var fallback = NativeNodeCountObjective.INSTANCE.evaluate(state);
        return new TypedSourceOnlySearch.Score(fallback.value(), Math.addExact(work.units, fallback.work()));
    }

    synchronized void remember(Expr expression, int count, Work work) {
        if (RetainedOperation.isObserved()) return;
        if (sizes.size() == CAPACITY) {
            work.add(CAPACITY + 1);
            sizes.clear();
        }
        sizes.put(expression, count);
        work.add(1);
    }

    synchronized Integer size(Expr expression, Work work) {
        work.add(1);
        return RetainedOperation.isObserved() ? null : sizes.get(expression);
    }

    @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
        visitor.reference(bounds); visitor.reference(sizes); visitor.reference(tactics); visitor.reference(goal); visitor.reference(disabledSchemas);
    }
}
