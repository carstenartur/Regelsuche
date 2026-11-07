package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.ast.iteration.OrderedReduction.Restriction;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import static de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;

/** A candidate factorization and its indexed access schedule for ordered rows. */
public record OrderedReductionPlan(List<Term> terms, List<Lookup> lookups, Optional<Lookup> eviction) {
    public OrderedReductionPlan {
        if (terms.size() > IterationPolynomial.MAX_TERMS || lookups.size() >= OrderedReduction.MAX_ARITY) {
            throw new IllegalArgumentException("ORDERED_PLAN_LIMIT");
        }
        terms = List.copyOf(terms);
        lookups = List.copyOf(lookups);
        Objects.requireNonNull(eviction);
    }

    public record Term(BigInteger coefficient, List<Moment> factors) {
        public Term {
            if (coefficient.signum() == 0 || coefficient.abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS
                    || factors.size() < 2 || factors.size() > OrderedReduction.MAX_ARITY) {
                throw new IllegalArgumentException("INVALID_ORDERED_TERM");
            }
            factors = List.copyOf(factors);
            if (factors.stream().mapToInt(moment -> moment.columns().size()).sum() > PairReduction.MAX_DEGREE) {
                throw new IllegalArgumentException("INVALID_ORDERED_TERM");
            }
        }
    }

    public enum Access { SCALAR, HASH, RANGE }

    public record Lookup(Restriction restriction, Access access) {
        public Lookup {
            Objects.requireNonNull(restriction);
            Objects.requireNonNull(access);
        }
    }

    public record Verification(Optional<Verified> verified, String reason, long work) {}

    static Verification verify(OrderedReduction source, OrderedReductionPlan plan, boolean windows,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(plan);
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            checkSchedule(source, plan, windows, work);
            var original = IterationPolynomial.parseOrderedSummand(source, work);
            var reconstruction = IterationPolynomial.zero(work);
            for (var term : plan.terms()) {
                var monomial = symbols(source, term, work);
                reconstruction = reconstruction.add(IterationPolynomial.single(
                    monomial, ExactRational.integer(term.coefficient()), work));
            }
            if (!reconstruction.same(original)) throw new Unavailable("ORDERED_IDENTITY_MISMATCH");
            return new Verification(Optional.of(new Verified(source, plan)), "VERIFIED", work.used());
        } catch (Unavailable unavailable) {
            return new Verification(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    private static IterationPolynomial.Monomial symbols(OrderedReduction source, Term term, Work work) {
        if (term.factors().size() != source.slots().size()) throw new Unavailable("ORDERED_ARITY_MISMATCH");
        var symbols = new ArrayList<SymbolId>();
        for (int slot = 0; slot < source.slots().size(); slot++) {
            work.charge(1);
            for (int column : term.factors().get(slot).columns()) {
                work.charge(1);
                if (column >= source.columnCount()) throw new Unavailable("UNKNOWN_COLUMN");
                symbols.add(source.slots().get(slot).get(column));
            }
        }
        return new IterationPolynomial.Monomial(symbols);
    }

    private static void checkSchedule(OrderedReduction source, OrderedReductionPlan plan, boolean windows, Work work) {
        work.charge(1);
        if (plan.lookups().size() != source.restrictions().size() || plan.eviction().isPresent() != windows
                || (windows && source.slots().size() != 2)) {
            throw new Unavailable("ORDERED_DOMAIN_MISMATCH");
        }
        for (int i = 0; i < plan.lookups().size(); i++) {
            work.charge(1);
            var lookup = plan.lookups().get(i);
            if (!lookup.restriction().equals(source.restrictions().get(i))) {
                throw new Unavailable("ORDERED_PREDICATE_MISMATCH");
            }
            checkAccess(lookup);
        }
        if (windows) {
            var reverse = plan.eviction().orElseThrow();
            if (!reversed(source.restrictions().getFirst(), reverse.restriction())) {
                throw new Unavailable("EVICTION_PREDICATE_MISMATCH");
            }
            checkAccess(reverse);
        }
    }

    private static void checkAccess(Lookup lookup) {
        boolean supported = switch (lookup.access()) {
            case SCALAR -> lookup.restriction() == All.ROWS;
            case HASH -> lookup.restriction() instanceof KeyInterval interval && interval.isPoint();
            case RANGE -> lookup.restriction() instanceof KeyInterval;
        };
        if (!supported) throw new Unavailable("INVALID_INDEX_ACCESS");
    }

    private static boolean reversed(Restriction original, Restriction candidate) {
        if (original == All.ROWS) return candidate == All.ROWS;
        return original instanceof KeyInterval forward && candidate instanceof KeyInterval reverse
            && forward.leftColumn() == reverse.rightColumn() && forward.rightColumn() == reverse.leftColumn()
            && forward.complement() == reverse.complement()
            && opposite(forward.lower(), reverse.upper()) && opposite(forward.upper(), reverse.lower());
    }

    private static boolean opposite(Optional<Bound> bound, Optional<Bound> opposite) {
        if (bound.isEmpty()) return opposite.isEmpty();
        var forward = bound.orElseThrow();
        return opposite.filter(reverse -> forward.inclusive() == reverse.inclusive()
            && forward.offset().add(reverse.offset()).signum() == 0).isPresent();
    }

    public record Evaluation(List<BigInteger> sums, long rowReads, long indexAccesses, long work) {
        public Evaluation {
            sums = List.copyOf(sums);
        }
    }

    /** Execution is restricted to a source-bound plan that passed the identity and schedule checks. */
    public static final class Verified {
        private final OrderedReduction source;
        private final OrderedReductionPlan plan;

        private Verified(OrderedReduction source, OrderedReductionPlan plan) {
            this.source = source;
            this.plan = plan;
        }

        public OrderedReduction source() { return source; }
        public OrderedReductionPlan plan() { return plan; }

        /** Returns a single sum over the ordered tuples. */
        public Evaluation evaluate(List<? extends List<BigInteger>> rows, IterationLimits limits,
                BooleanSupplier cancellation) {
            if (plan.eviction().isPresent()) throw new IllegalArgumentException("WINDOW_WIDTH_REQUIRED");
            return OrderedReductionEvaluation.evaluate(source, plan, rows, 0, limits, cancellation);
        }

        /** Returns one sum per complete window, in input order. */
        public Evaluation evaluateWindows(List<? extends List<BigInteger>> rows, int width, IterationLimits limits,
                BooleanSupplier cancellation) {
            if (width < 1 || plan.eviction().isEmpty()) throw new IllegalArgumentException("INVALID_WINDOW");
            return OrderedReductionEvaluation.evaluate(source, plan, rows, width, limits, cancellation);
        }
    }
}
