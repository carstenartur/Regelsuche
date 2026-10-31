package de.regelsuche.ast.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.symbol.SymbolId;
import java.util.*;

/** Immutable finite binders beside (not inside) the scalar AST. Invalid scope fails at construction. */
public sealed interface FiniteIteration permits FiniteIteration.Sum, FiniteIteration.Recurrence {
    int MAX_NODES = 2048, MAX_DEPTH = 64, MAX_PARAMETERS = 16, MAX_COEFFICIENT_BITS = 4096;
    IndexRange range();
    SymbolId index();
    Set<SymbolId> parameters();
    IntegerArithmetic arithmetic();

    record Sum(IndexRange range, SymbolId index, Set<SymbolId> parameters, Expr term,
               IntegerArithmetic arithmetic) implements FiniteIteration {
        public Sum {
            IterationScope.parameterCount(parameters);
            parameters = Set.copyOf(parameters);
            IterationScope.binders(range, index, null, parameters, arithmetic);
            IterationScope.validate(term, IterationScope.union(parameters, index));
        }
    }
    /** update is evaluated with the previous state; initial may mention parameters only. */
    record Recurrence(IndexRange range, SymbolId index, SymbolId state, Set<SymbolId> parameters,
                      Expr initial, Expr update, IntegerArithmetic arithmetic) implements FiniteIteration {
        public Recurrence {
            Objects.requireNonNull(state);
            IterationScope.parameterCount(parameters);
            parameters = Set.copyOf(parameters);
            IterationScope.binders(range, index, state, parameters, arithmetic);
            IterationScope.validate(initial, parameters);
            IterationScope.validate(update, IterationScope.union(IterationScope.union(parameters, index), state));
        }
    }
}
