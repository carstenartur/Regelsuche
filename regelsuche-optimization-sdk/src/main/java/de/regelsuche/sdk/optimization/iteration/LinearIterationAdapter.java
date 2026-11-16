package de.regelsuche.sdk.optimization.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.iteration.LinearIteration.State;
import de.regelsuche.math.algorithms.iteration.LinearIteration.Update;
import de.regelsuche.math.algorithms.iteration.LinearIteration.UpdateOrder;
import de.regelsuche.sdk.optimization.OptimizationBudget;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/** Maps the SDK's public transport records to the mathematical implementation. */
final class LinearIterationAdapter {
    private LinearIterationAdapter() {}

    static de.regelsuche.math.algorithms.iteration.LinearIteration source(SymbolId index, Expr indexStart,
            BigInteger indexStep, Expr count, List<LinearIteration.State> states, Set<SymbolId> parameters,
            List<LinearIteration.Update> updates, LinearIteration.UpdateOrder order, IntegerArithmetic arithmetic) {
        return new de.regelsuche.math.algorithms.iteration.LinearIteration(index, indexStart, indexStep, count,
            states.stream().map(state -> new State(state.symbol(), state.initial())).toList(), parameters,
            updates.stream().map(update -> new Update(update.state(), update.expression())).toList(),
            UpdateOrder.valueOf(order.name()), arithmetic);
    }

    static de.regelsuche.math.algorithms.iteration.LinearIterationOptimizer.Candidate candidate(
            LinearIterationOptimizer.Candidate candidate) {
        return new de.regelsuche.math.algorithms.iteration.LinearIterationOptimizer.Candidate(
            candidate.source().mathSource(), candidate.transition().mathTransition(), candidate.work());
    }

    static IterationLimits limits(OptimizationBudget budget) {
        return new IterationLimits(budget.maximumWork(), budget.maximumStates(), budget.timeoutMillis());
    }
}
