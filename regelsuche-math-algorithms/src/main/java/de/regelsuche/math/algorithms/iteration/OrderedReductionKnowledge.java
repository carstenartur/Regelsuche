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

/** A reusable fact acquired from a verified point-update plan. */
public final class OrderedReductionKnowledge {
    private final BinaryExpr pattern;
    private final Map<IterationPolynomial.Monomial, ExactRational> proof;
    private final OrderedPairStateProjection.Kernel pairState;

    private OrderedReductionKnowledge(BinaryExpr pattern, Map<IterationPolynomial.Monomial, ExactRational> proof,
            OrderedPairStateProjection.Kernel pairState) {
        this.pattern = pattern;
        this.proof = proof;
        this.pairState = pairState;
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
            return new Acquisition(Optional.of(new OrderedReductionKnowledge(pattern, polynomial.terms(), null)),
                "VERIFIED", work.used());
        } catch (Unavailable unavailable) {
            return new Acquisition(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    /** Retains a checked pair sum and its required row statistics for later triple reductions. */
    public static Acquisition learnPairState(OrderedBlockReductionPlan.Verified example, IterationLimits limits,
            BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            work.charge(1);
            if (example.source().slots().size() != 2) {
                return new Acquisition(Optional.empty(), "PAIR_STATE_REQUIRES_TWO_SLOTS", work.used());
            }
            var terms = example.plan().reduction().terms();
            if (terms.size() > limits.maximumTerms()) throw new Unavailable("TERM_LIMIT");
            var kernel = OrderedPairStateProjection.kernel(terms, work);
            var knowledge = kernel.map(value -> new OrderedReductionKnowledge(null, Map.of(), value));
            return new Acquisition(knowledge, knowledge.isPresent() ? "VERIFIED" : "NO_PAIR_STATE", work.used());
        } catch (Unavailable unavailable) {
            return new Acquisition(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    Optional<OrderedPairStateProjection.Kernel> pairState() {
        return Optional.ofNullable(pairState);
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
        if (pattern == null) return Optional.empty();
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
