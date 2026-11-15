package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;

/** An exact normalization lemma acquired from a verified point-update plan. */
public final class OrderedReductionKnowledge {
    private final BinaryExpr pattern;
    private final Map<IterationPolynomial.Monomial, ExactRational> proof;

    private OrderedReductionKnowledge(BinaryExpr pattern, Map<IterationPolynomial.Monomial, ExactRational> proof) {
        this.pattern = pattern;
        this.proof = proof;
    }

    public record Acquisition(Optional<OrderedReductionKnowledge> knowledge, String reason, long work) {}

    /** Learns the first compound power in a checked example, without access to later tasks. */
    public static Acquisition learn(OrderedBlockReductionPlan.Verified example, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            var selected = select(example.source().summand(), work);
            if (selected.isEmpty()) return new Acquisition(Optional.empty(), "NO_COMPOUND_POWER", work.used());
            var allowed = new HashSet<SymbolId>();
            for (var slot : example.source().slots()) {
                work.charge(slot.size());
                allowed.addAll(slot);
            }
            var pattern = selected.orElseThrow();
            var polynomial = IterationPolynomial.parseRowSummand(pattern, allowed, work);
            return new Acquisition(Optional.of(new OrderedReductionKnowledge(pattern, polynomial.terms())),
                "VERIFIED", work.used());
        } catch (Unavailable unavailable) {
            return new Acquisition(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    private static Optional<BinaryExpr> select(Expr expression, Work work) {
        work.charge(1);
        if (!(expression instanceof BinaryExpr binary)) return Optional.empty();
        if (binary.operator() == BinaryOperator.POW && binary.left() instanceof BinaryExpr
                && binary.right() instanceof NumberExpr exponent
                && exponent.value().numerator().compareTo(BigInteger.ONE) > 0) {
            return Optional.of(binary);
        }
        return select(binary.left(), work).or(() -> select(binary.right(), work));
    }

    Optional<IterationPolynomial> tryNormalize(BinaryExpr input, Set<SymbolId> allowed, Work work) {
        var bindings = new HashMap<SymbolId, SymbolId>();
        var used = new HashSet<SymbolId>();
        if (!matches(pattern, input, allowed, bindings, used, work)) return Optional.empty();
        return Optional.of(IterationPolynomial.rename(proof, bindings, work));
    }

    private static boolean matches(Expr pattern, Expr input, Set<SymbolId> allowed, Map<SymbolId, SymbolId> bindings,
            Set<SymbolId> used, Work work) {
        work.charge(1);
        return switch (pattern) {
            case NumberExpr number -> input instanceof NumberExpr actual && number.value().equals(actual.value());
            case VariableExpr variable -> input instanceof VariableExpr actual
                && bind(variable, actual, allowed, bindings, used);
            case BinaryExpr binary -> input instanceof BinaryExpr actual && binary.operator() == actual.operator()
                && matches(binary.right(), actual.right(), allowed, bindings, used, work)
                && matches(binary.left(), actual.left(), allowed, bindings, used, work);
            default -> false;
        };
    }

    private static boolean bind(VariableExpr variable, VariableExpr actual, Set<SymbolId> allowed,
            Map<SymbolId, SymbolId> bindings, Set<SymbolId> used) {
        var formal = variable.symbol().orElseThrow();
        var symbol = actual.symbol().orElseThrow();
        if (!allowed.contains(symbol)) return false;
        var previous = bindings.get(formal);
        if (previous != null) return previous.equals(symbol);
        if (!used.add(symbol)) return false;
        bindings.put(formal, symbol);
        return true;
    }
}
