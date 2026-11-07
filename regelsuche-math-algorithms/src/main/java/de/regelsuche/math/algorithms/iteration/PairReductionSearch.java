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

    public static Result search(PairReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        var result = IterationSearch.search(new Source(source), new PairDomain(source), limits, maximumStates, cancellation);
        var witness = result.witness().stream().map(step -> new CheckedMove(step.ruleId(),
            step.synthesisWork(), step.verificationWork())).toList();
        return new Result(result.verified(), result.reason(), witness, result.metrics());
    }

    private sealed interface Form permits Source, Normalized, Reduced {}
    private record Source(PairReduction value) implements Form {}
    private record Normalized(PairReductionSynthesis.NormalForm value) implements Form {}
    private record Reduced(PairReductionPlan value) implements Form {}

    private record PairDomain(PairReduction original) implements IterationSearch.Domain<Form, PairReductionVerifier.Verified> {
        @Override public String family() { return "pair-reduction"; }
        @Override public String noPlanReason() { return "NO_PAIR_PLAN"; }
        @Override public int maximumDepth() { return 2; }
        @Override public boolean finished(Form form) { return form instanceof Reduced; }

        @Override public IterationSearch.Proposal<Form> propose(Form source, IterationLimits limits, BooleanSupplier cancellation) {
            return switch (source) {
                case Source input -> {
                    var proposal = SYNTHESIS.normalize(input.value(), limits, cancellation);
                    yield new IterationSearch.Proposal<>("normalize-pair-summand",
                        proposal.value().map(Normalized::new), proposal.reason(), proposal.work());
                }
                case Normalized normalized -> {
                    var proposal = SYNTHESIS.reduce(normalized.value(), limits, cancellation);
                    yield new IterationSearch.Proposal<>("reduce-pair-sum",
                        proposal.value().map(Reduced::new), proposal.reason(), proposal.work());
                }
                case Reduced ignored -> throw new IllegalStateException("pair plan is terminal");
            };
        }

        @Override public IterationSearch.Check<PairReductionVerifier.Verified> verify(
                Form source, Form target, IterationLimits limits, BooleanSupplier cancellation) {
            return switch (target) {
                case Normalized normalized -> {
                    var check = VERIFIER.verifyNormalization(original, normalized.value(), limits, cancellation);
                    yield new IterationSearch.Check<>(check.accepted(), Optional.empty(), check.reason(), check.work());
                }
                case Reduced reduced -> {
                    var check = VERIFIER.verify(original, reduced.value(), limits, cancellation);
                    yield new IterationSearch.Check<>(check.verified().isPresent(), check.verified(), check.reason(), check.work());
                }
                case Source ignored -> throw new IllegalArgumentException("unexpected pair proposal");
            };
        }
    }
}
