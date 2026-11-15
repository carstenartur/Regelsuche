package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.OrderedReduction;
import de.regelsuche.ast.iteration.OrderedReduction.All;
import de.regelsuche.ast.iteration.OrderedReduction.Bound;
import de.regelsuche.ast.iteration.OrderedReduction.KeyInterval;
import de.regelsuche.ast.iteration.OrderedReduction.Restriction;
import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.math.algorithms.iteration.PairReductionPlan.Moment;
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

    public record PointUpdateResult(Optional<OrderedBlockReductionPlan.Verified> verified, String reason,
            List<CheckedMove> witness, MoveSearch.Metrics metrics) {
        public PointUpdateResult {
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
        var result = run(source, new Indexed(windows), limits, maximumStates, cancellation);
        return new Result(result.verified(), result.reason(), witness(result), result.metrics());
    }

    /** Derives and checks a block summary for fixed-length row replacements. */
    public static PointUpdateResult searchPointUpdates(OrderedReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        return pointUpdates(source, new PointUpdates(null), limits, maximumStates, cancellation);
    }

    /** Uses a previously checked polynomial lemma during point-update plan synthesis. */
    public static PointUpdateResult searchPointUpdates(OrderedReduction source, OrderedReductionKnowledge knowledge,
            IterationLimits limits, int maximumStates, BooleanSupplier cancellation) {
        return pointUpdates(source, new PointUpdates(Objects.requireNonNull(knowledge)), limits, maximumStates, cancellation);
    }

    /** Supplies only the block statistics. The source coefficients and every cut are still checked. */
    static PointUpdateResult searchWithBlockStatistics(OrderedReduction source, List<List<Moment>> statistics,
            IterationLimits limits, int maximumStates, BooleanSupplier cancellation) {
        return pointUpdates(source, new SuppliedBlockStatistics(Objects.requireNonNull(statistics)),
            limits, maximumStates, cancellation);
    }

    private static PointUpdateResult pointUpdates(OrderedReduction source,
            PlanKind<OrderedBlockReductionPlan, OrderedBlockReductionPlan.Verified> kind,
            IterationLimits limits, int maximumStates, BooleanSupplier cancellation) {
        var result = run(source, kind, limits, maximumStates, cancellation);
        return new PointUpdateResult(result.verified(), result.reason(), witness(result), result.metrics());
    }

    private static <P, V> IterationSearch.Result<V> run(OrderedReduction source, PlanKind<P, V> kind,
            IterationLimits limits, int maximumStates, BooleanSupplier cancellation) {
        return IterationSearch.search(new Input<>(), new Domain<>(Objects.requireNonNull(source), kind),
            limits, maximumStates, cancellation);
    }

    private static List<CheckedMove> witness(IterationSearch.Result<?> result) {
        return result.witness().stream()
            .map(step -> new CheckedMove(step.ruleId(), step.synthesisWork(), step.verificationWork())).toList();
    }

    public static OrderedReductionPlan.Verification verify(OrderedReduction source, OrderedReductionPlan plan,
            boolean windows, IterationLimits limits, BooleanSupplier cancellation) {
        return OrderedReductionPlan.verify(source, plan, windows, limits, cancellation);
    }

    public static OrderedBlockReductionPlan.Verification verifyPointUpdates(OrderedReduction source,
            OrderedBlockReductionPlan plan, IterationLimits limits, BooleanSupplier cancellation) {
        var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
        try {
            var checked = OrderedBlockReductionPlan.check(source, plan, work);
            return new OrderedBlockReductionPlan.Verification(Optional.of(checked), "VERIFIED", work.used());
        } catch (Unavailable unavailable) {
            return new OrderedBlockReductionPlan.Verification(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    private sealed interface Form<P> permits Input, Normalized, Candidate {}
    private record Input<P>() implements Form<P> {}
    private record Normalized<P>(Map<IterationPolynomial.Monomial, ExactRational> terms) implements Form<P> {}
    private record Candidate<P>(P plan) implements Form<P> {}

    private interface PlanKind<P, V> {
        String rule();
        default OrderedReductionKnowledge knowledge() { return null; }
        default void checkSource(OrderedReduction source, Work work) {}
        P derive(OrderedReduction source, Map<IterationPolynomial.Monomial, ExactRational> terms, Work work);
        V check(OrderedReduction source, P plan, Work work);
    }

    private record Indexed(boolean windows) implements PlanKind<OrderedReductionPlan, OrderedReductionPlan.Verified> {
        @Override public String rule() { return "derive-indexed-reduction"; }

        @Override public OrderedReductionPlan derive(OrderedReduction source,
                Map<IterationPolynomial.Monomial, ExactRational> terms, Work work) {
            return factor(source, terms, windows, work);
        }

        @Override public OrderedReductionPlan.Verified check(OrderedReduction source, OrderedReductionPlan plan, Work work) {
            return OrderedReductionPlan.check(source, plan, windows, work);
        }
    }

    private record PointUpdates(OrderedReductionKnowledge knowledge)
            implements PlanKind<OrderedBlockReductionPlan, OrderedBlockReductionPlan.Verified> {
        @Override public String rule() { return "derive-block-reduction"; }

        @Override public void checkSource(OrderedReduction source, Work work) {
            OrderedBlockReductionPlan.checkSource(source, work);
        }

        @Override public OrderedBlockReductionPlan derive(OrderedReduction source,
                Map<IterationPolynomial.Monomial, ExactRational> terms, Work work) {
            return OrderedBlockReductionPlan.derive(factor(source, terms, false, work), work);
        }

        @Override public OrderedBlockReductionPlan.Verified check(OrderedReduction source, OrderedBlockReductionPlan plan,
                Work work) {
            return OrderedBlockReductionPlan.check(source, plan, work);
        }
    }

    private record SuppliedBlockStatistics(List<List<Moment>> statistics)
            implements PlanKind<OrderedBlockReductionPlan, OrderedBlockReductionPlan.Verified> {
        @Override public String rule() { return "use-supplied-block-statistics"; }

        @Override public void checkSource(OrderedReduction source, Work work) {
            OrderedBlockReductionPlan.checkSource(source, work);
        }

        @Override public OrderedBlockReductionPlan derive(OrderedReduction source,
                Map<IterationPolynomial.Monomial, ExactRational> terms, Work work) {
            return OrderedBlockReductionPlan.supply(factor(source, terms, false, work), statistics, work);
        }

        @Override public OrderedBlockReductionPlan.Verified check(OrderedReduction source, OrderedBlockReductionPlan plan,
                Work work) {
            return OrderedBlockReductionPlan.check(source, plan, work);
        }
    }

    private record Domain<P, V>(OrderedReduction source, PlanKind<P, V> kind)
            implements IterationSearch.Domain<Form<P>, V> {
        @Override public String family() { return "ordered-reduction"; }
        @Override public String noPlanReason() { return "NO_ORDERED_PLAN"; }
        @Override public int maximumDepth() { return 2; }
        @Override public boolean finished(Form<P> form) { return form instanceof Candidate<?>; }

        @Override public IterationSearch.Proposal<Form<P>> propose(Form<P> form, IterationLimits limits,
                BooleanSupplier cancellation) {
            String rule = form instanceof Input<?> ? "normalize-ordered-summand" : kind.rule();
            var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE, kind.knowledge());
            try {
                Form<P> candidate = switch (form) {
                    case Input<P> ignored -> {
                        kind.checkSource(source, work);
                        yield new Normalized<>(IterationPolynomial.parseOrderedSummand(source, work).terms());
                    }
                    case Normalized<P>(var terms) -> new Candidate<>(kind.derive(source, terms, work));
                    case Candidate<P> ignored -> throw new IllegalStateException("ordered plan is terminal");
                };
                return new IterationSearch.Proposal<>(rule, Optional.of(candidate), "CANDIDATE", work.used());
            } catch (Unavailable unavailable) {
                return new IterationSearch.Proposal<>(rule, Optional.empty(), unavailable.getMessage(), work.used());
            }
        }

        @Override public IterationSearch.Check<V> verify(Form<P> from, Form<P> to,
                IterationLimits limits, BooleanSupplier cancellation) {
            var work = new Work(limits, cancellation, PairReduction.MAX_DEGREE);
            try {
                if (to instanceof Candidate<P>(var plan)) {
                    return new IterationSearch.Check<>(true, Optional.of(kind.check(source, plan, work)),
                        "VERIFIED", work.used());
                }
                var normalized = (Normalized<P>) to;
                var original = IterationPolynomial.parseOrderedSummand(source, work);
                work.charge(original.terms().size() + normalized.terms().size());
                if (!original.terms().equals(normalized.terms())) throw new Unavailable("SUMMAND_MISMATCH");
                return new IterationSearch.Check<>(true, Optional.empty(), "VERIFIED_NORMALIZATION", work.used());
            } catch (Unavailable unavailable) {
                return new IterationSearch.Check<>(false, Optional.empty(), unavailable.getMessage(), work.used());
            }
        }
    }

    private static OrderedReductionPlan factor(OrderedReduction source, Map<IterationPolynomial.Monomial, ExactRational> polynomial,
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
