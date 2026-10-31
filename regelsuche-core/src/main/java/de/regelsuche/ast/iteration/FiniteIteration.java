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
            parameters = scope(range, index, null, parameters, arithmetic);
            validate(term, union(parameters, index));
        }
    }
    /** update is evaluated with the previous state; initial may mention parameters only. */
    record Recurrence(IndexRange range, SymbolId index, SymbolId state, Set<SymbolId> parameters,
                      Expr initial, Expr update, IntegerArithmetic arithmetic) implements FiniteIteration {
        public Recurrence {
            Objects.requireNonNull(state);
            parameters = scope(range, index, state, parameters, arithmetic);
            validate(initial, parameters);
            validate(update, union(union(parameters, index), state));
        }
    }
    private static Set<SymbolId> scope(IndexRange range, SymbolId index, SymbolId state,
                                       Set<SymbolId> parameters, IntegerArithmetic arithmetic) {
        Objects.requireNonNull(range); Objects.requireNonNull(index); Objects.requireNonNull(arithmetic);
        if (parameters.size() > MAX_PARAMETERS) throw new IllegalArgumentException("TOO_MANY_PARAMETERS");
        var copy = Set.copyOf(parameters);
        if (index.equals(state) || copy.contains(index) || (state != null && copy.contains(state)))
            throw new IllegalArgumentException("COLLIDING_BINDERS");
        return copy;
    }
    private static Set<SymbolId> union(Set<SymbolId> symbols, SymbolId extra) {
        var result = new HashSet<>(symbols); result.add(extra); return result;
    }
    private static void validate(Expr expression, Set<SymbolId> allowed) {
        record Node(Expr expression, int depth) {}
        var pending = new ArrayDeque<Node>(); pending.push(new Node(Objects.requireNonNull(expression), 1));
        int count = 0;
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (++count > MAX_NODES || node.depth() > MAX_DEPTH) throw new IllegalArgumentException("EXPRESSION_LIMIT");
            switch (node.expression()) {
                case VariableExpr v -> {
                    if (v.symbol().isEmpty() || !allowed.contains(v.symbol().orElseThrow()))
                        throw new IllegalArgumentException("UNBOUND_SYMBOL");
                }
                case NumberExpr n -> {
                    if (n.value().numerator().bitLength() > MAX_COEFFICIENT_BITS || n.value().denominator().bitLength() > MAX_COEFFICIENT_BITS)
                        throw new IllegalArgumentException("COEFFICIENT_LIMIT");
                }
                case BinaryExpr b -> {
                    pending.push(new Node(b.left(), node.depth()+1)); pending.push(new Node(b.right(), node.depth()+1));
                }
                case FunctionExpr f -> {
                    if (f.name().length() > 128 || f.arguments().size() > MAX_NODES - count - pending.size())
                        throw new IllegalArgumentException("EXPRESSION_LIMIT");
                    for (Expr arg : f.arguments()) pending.push(new Node(arg, node.depth()+1));
                }
            }
        }
    }
}
