package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.PairReduction;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Source-only search for a checked one-pass pair plan on the shared move frontier. */
public final class PairReductionSearch {
    private static final PairReductionSynthesis SYNTHESIS = new PairReductionSynthesis();
    private static final PairReductionVerifier VERIFIER = new PairReductionVerifier();

    private PairReductionSearch() {}

    public record CheckedMove(String ruleId, long synthesisWork, long verificationWork) {}

    public record Result(Optional<PairReductionVerifier.Verified> verified, String reason,
            List<CheckedMove> witness, MoveSearch.Metrics metrics) {
        public Result {
            witness = List.copyOf(witness);
        }
    }

    public record WindowResult(Optional<RollingPairReductionPlan.Verified> verified, String reason,
            List<CheckedMove> witness, MoveSearch.Metrics metrics) {
        public WindowResult {
            witness = List.copyOf(witness);
        }
    }

    public static Result search(PairReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        var domain = new PairDomain<PairReductionPlan, PairReductionVerifier.Verified>(source, "reduce-pair-sum",
            SYNTHESIS::reduce, (plan, allowance, stop) -> {
                var check = VERIFIER.verify(source, plan, allowance, stop);
                return new IterationSearch.Check<>(check.verified().isPresent(), check.verified(), check.reason(), check.work());
            });
        var result = IterationSearch.search(new Source<>(source), domain, limits, maximumStates, cancellation);
        return new Result(result.verified(), result.reason(), moves(result.witness()), result.metrics());
    }

    /** Derives a plan for consecutive windows of any positive runtime width. */
    public static WindowResult searchWindows(PairReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        var domain = new PairDomain<RollingPairReductionPlan, RollingPairReductionPlan.Verified>(source, "reduce-pair-windows",
            SYNTHESIS::reduceWindows, (plan, allowance, stop) -> {
                var check = verifyWindows(source, plan, allowance, stop);
                return new IterationSearch.Check<>(check.verified().isPresent(), check.verified(), check.reason(), check.work());
            });
        var result = IterationSearch.search(new Source<>(source), domain, limits, maximumStates, cancellation);
        return new WindowResult(result.verified(), result.reason(), moves(result.witness()), result.metrics());
    }

    public static RollingPairReductionPlan.Verification verifyWindows(PairReduction source, RollingPairReductionPlan plan,
            IterationLimits limits, BooleanSupplier cancellation) {
        return RollingPairReductionPlan.verify(source, plan, limits, cancellation);
    }

    private static List<CheckedMove> moves(List<IterationSearch.CheckedMove> steps) {
        return steps.stream().map(step -> new CheckedMove(step.ruleId(), step.synthesisWork(), step.verificationWork())).toList();
    }

    private sealed interface Form<P> permits Source, Normalized, Reduced {}
    private record Source<P>(PairReduction value) implements Form<P> {}
    private record Normalized<P>(PairReductionSynthesis.NormalForm value) implements Form<P> {}
    private record Reduced<P>(P value) implements Form<P> {}

    private interface Reduce<P> {
        PairReductionSynthesis.Result<P> apply(PairReductionSynthesis.NormalForm form, IterationLimits limits,
                BooleanSupplier cancellation);
    }

    private interface Verify<P, V> {
        IterationSearch.Check<V> apply(P plan, IterationLimits limits, BooleanSupplier cancellation);
    }

    private record PairDomain<P, V>(PairReduction original, String rule, Reduce<P> reduction, Verify<P, V> checker)
            implements IterationSearch.Domain<Form<P>, V> {
        @Override public String family() { return "pair-reduction"; }
        @Override public String noPlanReason() { return "NO_PAIR_PLAN"; }
        @Override public int maximumDepth() { return 2; }
        @Override public boolean finished(Form<P> form) { return form instanceof Reduced<P>; }

        @Override public IterationSearch.Proposal<Form<P>> propose(Form<P> source, IterationLimits limits,
                BooleanSupplier cancellation) {
            return switch (source) {
                case Source(var input) -> {
                    var proposal = SYNTHESIS.normalize(input, limits, cancellation);
                    yield new IterationSearch.Proposal<>("normalize-pair-summand",
                        proposal.value().map(Normalized<P>::new), proposal.reason(), proposal.work());
                }
                case Normalized(var normalized) -> {
                    var proposal = reduction.apply(normalized, limits, cancellation);
                    yield new IterationSearch.Proposal<>(rule,
                        proposal.value().map(Reduced<P>::new), proposal.reason(), proposal.work());
                }
                case Reduced<P> ignored -> throw new IllegalStateException("pair plan is terminal");
            };
        }

        @Override public IterationSearch.Check<V> verify(
                Form<P> source, Form<P> target, IterationLimits limits, BooleanSupplier cancellation) {
            return switch (target) {
                case Normalized(var normalized) -> {
                    var check = VERIFIER.verifyNormalization(original, normalized, limits, cancellation);
                    yield new IterationSearch.Check<>(check.accepted(), Optional.empty(), check.reason(), check.work());
                }
                case Reduced(var reduced) -> checker.apply(reduced, limits, cancellation);
                case Source<P> ignored -> throw new IllegalArgumentException("unexpected pair proposal");
            };
        }
    }
}
