package de.regelsuche.ast.iteration;

import de.regelsuche.ast.*;
import de.regelsuche.symbol.SymbolId;
import java.util.*;
import static de.regelsuche.ast.iteration.FiniteIteration.*;

/** Bounded construction checks shared by the finite binder records. */
final class IterationScope {
    private IterationScope() {}
    static void parameterCount(Set<SymbolId> parameters) {
        if (parameters.size() > MAX_PARAMETERS) throw new IllegalArgumentException("TOO_MANY_PARAMETERS");
    }
    static void binders(IndexRange range, SymbolId index, SymbolId state,
                        Set<SymbolId> parameters, IntegerArithmetic arithmetic) {
        Objects.requireNonNull(range); Objects.requireNonNull(index); Objects.requireNonNull(arithmetic);
        if (index.equals(state) || parameters.contains(index) || (state != null && parameters.contains(state)))
            throw new IllegalArgumentException("COLLIDING_BINDERS");
    }
    static Set<SymbolId> union(Set<SymbolId> symbols, SymbolId extra) {
        var result = new HashSet<>(symbols); result.add(extra); return result;
    }
    static void validate(Expr expression, Set<SymbolId> allowed) {
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
