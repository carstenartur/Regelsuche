package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.ast.iteration.OrderedReduction.Restriction;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.search.moves.MoveSearch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;

/** Derives ordered tuple and window plans through the shared checked move frontier. */
public final class OrderedReductionSearch {
    private OrderedReductionSearch() {}

    public record CheckedMove(String ruleId, long synthesisWork, long verificationWork) {}

    public record Result(Optional<OrderedReductionPlan.Verified> verified, String reason,
            List<CheckedMove> witness, MoveSearch.Metrics metrics) {
        public Result {
            witness = List.copyOf(witness);
        }
    }

    public static Result search(OrderedReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        return search(source, false, limits, maximumStates, cancellation);
    }

    public static Result searchWindows(OrderedReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        if (source.slots().size() != 2) throw new IllegalArgumentException("WINDOW_REQUIRES_PAIR");
        return search(source, true, limits, maximumStates, cancellation);
    }

    private static Result search(OrderedReduction source, boolean windows, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        var result = IterationSearch.search(new Input(), new Domain(Objects.requireNonNull(source), windows),
            limits, maximumStates, cancellation);
        return new Result(result.verified(), result.reason(), result.witness().stream()
            .map(step -> new CheckedMove(step.ruleId(), step.synthesisWork(), step.verificationWork())).toList(),
            result.metrics());
    }

    public static OrderedReductionPlan.Verification verify(OrderedReduction source, OrderedReductionPlan plan,
            boolean windows, IterationLimits limits, BooleanSupplier cancellation) {
        return OrderedReductionPlan.verify(source, plan, windows, limits, cancellation);
    }

    private sealed interface Form permits Input, Normalized, Candidate {}
    private record Input() implements Form {}
    private record Normalized(Map<IterationPolynomial.Monomial, ExactRational> terms) implements Form {}
    private record Candidate(OrderedReductionPlan plan) implements Form {}

    private record Domain(OrderedReduction source, boolean windows)
            implements IterationSearch.Domain<Form, OrderedReductionPlan.Verified> {
        @Override public String family() { return "ordered-reduction"; }
        @Override public String noPlanReason() { return "NO_ORDERED_PLAN"; }
        @Override public int maximumDepth() { return 2; }
        @Override public boolean finished(Form form) { return form instanceof Candidate; }

        @Override public IterationSearch.Proposal<Form> propose(Form form, IterationLimits limits,
                BooleanSupplier cancellation) {
            String rule = form instanceof Input ? "normalize-ordered-summand" : "derive-indexed-reduction";
            var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
            try {
                Form candidate = switch (form) {
                    case Input ignored -> new Normalized(IterationPolynomial.parseOrderedSummand(source, work).terms());
                    case Normalized(var terms) -> new Candidate(derive(source, terms, windows, work));
                    case Candidate ignored -> throw new IllegalStateException("ordered plan is terminal");
                };
                return new IterationSearch.Proposal<>(rule, Optional.of(candidate), "CANDIDATE", work.used());
            } catch (Unavailable unavailable) {
                return new IterationSearch.Proposal<>(rule, Optional.empty(), unavailable.getMessage(), work.used());
            }
        }

        @Override public IterationSearch.Check<OrderedReductionPlan.Verified> verify(Form from, Form to,
                IterationLimits limits, BooleanSupplier cancellation) {
            if (to instanceof Candidate candidate) {
                var check = OrderedReductionPlan.verify(source, candidate.plan(), windows, limits, cancellation);
                return new IterationSearch.Check<>(check.verified().isPresent(), check.verified(),
                    check.reason(), check.work());
            }
            var normalized = (Normalized) to;
            var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
            try {
                var original = IterationPolynomial.parseOrderedSummand(source, work);
                work.charge(original.terms().size() + normalized.terms().size());
                if (!original.terms().equals(normalized.terms())) throw new Unavailable("SUMMAND_MISMATCH");
                return new IterationSearch.Check<>(true, Optional.empty(), "VERIFIED_NORMALIZATION", work.used());
            } catch (Unavailable unavailable) {
                return new IterationSearch.Check<>(false, Optional.empty(), unavailable.getMessage(), work.used());
            }
        }
    }

    private static OrderedReductionPlan derive(OrderedReduction source, Map<IterationPolynomial.Monomial, ExactRational> polynomial,
            boolean windows, Work work) {
        var terms = new ArrayList<OrderedReductionPlan.Term>();
        for (var entry : RowMoments.split(source.slots(), polynomial, work).entrySet()) {
            work.charge(1);
            if (entry.getValue().abs().bitLength() > FiniteIteration.MAX_COEFFICIENT_BITS) {
                throw new Unavailable("COEFFICIENT_LIMIT");
            }
            terms.add(new OrderedReductionPlan.Term(entry.getValue(), entry.getKey()));
        }
        work.charge(source.restrictions().size());
        var lookups = source.restrictions().stream().map(OrderedReductionSearch::lookup).toList();
        var eviction = windows ? Optional.of(lookup(reverse(source.restrictions().getFirst())))
            : Optional.<OrderedReductionPlan.Lookup>empty();
        return new OrderedReductionPlan(terms, lookups, eviction);
    }

    private static OrderedReductionPlan.Lookup lookup(Restriction condition) {
        var access = switch (condition) {
            case All ignored -> OrderedReductionPlan.Access.SCALAR;
            case KeyInterval interval -> interval.isPoint() ? OrderedReductionPlan.Access.HASH
                : OrderedReductionPlan.Access.RANGE;
        };
        return new OrderedReductionPlan.Lookup(condition, access);
    }

    private static Restriction reverse(Restriction condition) {
        return switch (condition) {
            case All ignored -> All.ROWS;
            case KeyInterval interval -> new KeyInterval(interval.rightColumn(), interval.leftColumn(),
                interval.upper().map(OrderedReductionSearch::negate), interval.lower().map(OrderedReductionSearch::negate),
                interval.complement());
        };
    }

    private static Bound negate(Bound bound) {
        return new Bound(bound.offset().negate(), bound.inclusive());
    }
}
