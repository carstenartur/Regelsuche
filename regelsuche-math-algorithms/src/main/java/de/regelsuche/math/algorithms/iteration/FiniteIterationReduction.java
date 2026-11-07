package de.regelsuche.math.algorithms.iteration;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.MAX_DEGREE;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.MAX_TERMS;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.parse;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.single;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.variable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.zero;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Bounded telescoping for polynomial sums and additive recurrences. */
public final class FiniteIterationReduction {
    private FiniteIterationReduction() {}

    /** The antidifference is bound to the original range, scope, and arithmetic. */
    public record Certificate(FiniteIteration source, Expr antidifference) {
        public Certificate {
            Objects.requireNonNull(source);
            Objects.requireNonNull(antidifference);
        }
    }

    /** Counts arithmetic operations in the original iteration and the scalar result. */
    public record Cost(BigInteger originalOperations, long resultOperations) {
        public Cost {
            Objects.requireNonNull(originalOperations);
            if (originalOperations.signum() < 0 || resultOperations < 0) {
                throw new IllegalArgumentException("NEGATIVE_COST");
            }
        }
    }

    public record Plan(Expr result, Certificate certificate, Cost cost) {
        public Plan {
            Objects.requireNonNull(result);
            Objects.requireNonNull(certificate);
            Objects.requireNonNull(cost);
        }
    }

    public record Proposal(Optional<Plan> plan, String reason, long work) {}

    /** A successful check supplies its own arithmetic cost. */
    public record Verification(boolean verified, String reason, long work, Cost cost) {}

    public static Proposal synthesize(FiniteIteration source, IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        var work = new Work(limits, cancellation, MAX_DEGREE + 1);
        try {
            var parsed = parseSource(source, work);
            var primitive = antidifference(source, parsed.term(), work);
            Expr result = result(source, primitive, parsed.initial()).expression();
            Expr proof = primitive.expression();
            // Generated trees must fit the limits imposed on supplied expressions.
            parse(proof, symbols(source, false), false, MAX_DEGREE + 1, work);
            parse(result, source.parameters(), true, MAX_DEGREE, work);
            var plan = new Plan(result, new Certificate(source, proof), cost(source, result, work));
            return new Proposal(Optional.of(plan), "CANDIDATE", work.used());
        } catch (Unavailable unavailable) {
            return new Proposal(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    /** Check the proposed result by reconstructing the discrete difference and the range endpoints. */
    public static Verification verify(FiniteIteration source, Expr result, Certificate certificate,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(result);
        Objects.requireNonNull(certificate);
        var work = new Work(limits, cancellation, MAX_DEGREE + 1);
        try {
            work.charge(1);
            // Account for bounded source comparison before comparing the trees.
            work.charge(2L * FiniteIteration.MAX_NODES);
            if (!source.equals(certificate.source())) throw new Unavailable("SOURCE_MISMATCH");
            var parsed = parseSource(source, work);
            var primitive = parse(certificate.antidifference(), symbols(source, false), false, MAX_DEGREE + 1, work);
            var delta = primitive.shifted(source.index(), source.range().step()).subtract(primitive);
            if (!delta.same(parsed.term())) throw new Unavailable("ANTIDIFFERENCE_MISMATCH");

            var expected = result(source, primitive, parsed.initial());
            // Validate integral coefficients before modular reduction.
            var actual = parse(result, source.parameters(), true, MAX_DEGREE, work).integral(source.arithmetic());
            if (!expected.same(actual)) throw new Unavailable("RESULT_MISMATCH");
            var cost = cost(source, result, work);
            return new Verification(true, "VERIFIED_TELESCOPING", work.used(), cost);
        } catch (Unavailable unavailable) {
            return new Verification(false, unavailable.getMessage(), work.used(), null);
        }
    }

    private static IterationPolynomial antidifference(FiniteIteration source, IterationPolynomial term, Work work) {
        var remaining = term;
        var antidifference = zero(work);
        // Eliminate the leading index degree using the discrete difference of its next power.
        int rounds = 0;
        while (!remaining.terms().isEmpty()) {
            work.charge(remaining.terms().size());
            if (++rounds > MAX_TERMS * (MAX_DEGREE + 1)) throw new Unavailable("SYNTHESIS_LIMIT");
            var leading = remaining.terms().entrySet().stream()
                .max(Comparator.comparingInt(entry -> entry.getKey().degree(source.index()))).orElseThrow();
            int degree = leading.getKey().degree(source.index()) + 1;
            var denominator = ExactRational.integer(source.range().step().multiply(BigInteger.valueOf(degree)));
            var primitive = single(leading.getKey(), leading.getValue().divide(denominator), work)
                .multiply(variable(source.index(), work));
            antidifference = antidifference.add(primitive);
            remaining = remaining.subtract(primitive.shifted(source.index(), source.range().step()).subtract(primitive));
        }
        return antidifference;
    }

    private static IterationPolynomial result(FiniteIteration source, IterationPolynomial primitive,
            IterationPolynomial initial) {
        return primitive.at(source.index(), source.range().endExclusive())
            .subtract(primitive.at(source.index(), source.range().start())).add(initial).integral(source.arithmetic());
    }

    private static Set<SymbolId> symbols(FiniteIteration source, boolean state) {
        var allowed = new HashSet<>(source.parameters());
        allowed.add(source.index());
        if (state && source instanceof FiniteIteration.Recurrence recurrence) allowed.add(recurrence.state());
        return allowed;
    }

    private record Source(IterationPolynomial term, IterationPolynomial initial) {}

    private static Source parseSource(FiniteIteration source, Work work) {
        work.charge(1);
        if (source instanceof FiniteIteration.Sum sum) {
            return new Source(parse(sum.term(), symbols(source, false), true, MAX_DEGREE, work), zero(work));
        }
        var recurrence = (FiniteIteration.Recurrence) source;
        var update = parse(recurrence.update(), symbols(source, true), true, MAX_DEGREE, work);
        var term = update.subtract(variable(recurrence.state(), work));
        if (!term.independent(recurrence.state())) throw new Unavailable("NONLINEAR_STATE_UPDATE");
        var initial = parse(recurrence.initial(), source.parameters(), true, MAX_DEGREE, work);
        return new Source(term, initial);
    }

    private static long operations(Expr expression, Work work, int depth, int[] nodes) {
        work.charge(1);
        if (depth > FiniteIteration.MAX_DEPTH || ++nodes[0] > FiniteIteration.MAX_NODES) {
            throw new Unavailable("EXPRESSION_LIMIT");
        }
        return expression instanceof BinaryExpr binary
            ? 1 + operations(binary.left(), work, depth + 1, nodes) + operations(binary.right(), work, depth + 1, nodes)
            : 0;
    }

    private static Cost cost(FiniteIteration source, Expr result, Work work) {
        long perIteration;
        long initial = 0;
        if (source instanceof FiniteIteration.Sum sum) {
            perIteration = 1 + operations(sum.term(), work, 1, new int[]{0});
        } else {
            var recurrence = (FiniteIteration.Recurrence) source;
            perIteration = operations(recurrence.update(), work, 1, new int[]{0});
            initial = operations(recurrence.initial(), work, 1, new int[]{0});
        }
        var original = source.range().count().multiply(BigInteger.valueOf(perIteration)).add(BigInteger.valueOf(initial));
        return new Cost(original, operations(result, work, 1, new int[]{0}));
    }
}
