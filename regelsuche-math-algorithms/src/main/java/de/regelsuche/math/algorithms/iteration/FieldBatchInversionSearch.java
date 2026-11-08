package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FieldInversionBatch;
import de.regelsuche.math.algorithms.iteration.FieldBatchInversionPlan.Verified;
import de.regelsuche.math.algorithms.iteration.FieldInversionSearch.CheckedMove;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;

/** Synthesize and replay inversion batches using the shared iteration search. */
public final class FieldBatchInversionSearch {
    private FieldBatchInversionSearch() {}

    public record Result(Optional<Verified> verified, String reason, List<CheckedMove> witness, MoveSearch.Metrics metrics) {
        public Result {
            witness = List.copyOf(witness);
        }
    }

    public record Verification(Optional<Verified> verified, String reason, long work) {}

    public static Result search(FieldInversionBatch source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        var domain = new Domain(Objects.requireNonNull(source));
        var run = IterationSearch.search(new Input(), domain, limits, maximumStates, cancellation);
        return new Result(run.verified(), run.reason(), run.witness().stream()
            .map(step -> new CheckedMove(step.ruleId(), step.synthesisWork(), step.verificationWork())).toList(),
            run.metrics());
    }

    public static Verification verify(FieldInversionBatch source, FieldBatchInversionPlan plan,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(plan);
        var work = new Work(limits, cancellation, 0);
        try {
            checkLimits(source, limits, work);
            var verified = FieldBatchInversionPlan.check(source, plan, work::charge);
            return new Verification(verified, verified.isPresent() ? "VERIFIED" : "INVALID_FIELD_BATCH_SCHEDULE", work.used());
        } catch (Unavailable unavailable) {
            return new Verification(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    private static void checkLimits(FieldInversionBatch source, IterationLimits limits, Work work) {
        work.charge(1);
        if (source.operands() > limits.maximumTerms()) throw new Unavailable("FIELD_BATCH_OPERAND_LIMIT");
    }

    private sealed interface Form permits Input, Planned {}
    private record Input() implements Form {}
    private record Planned(FieldBatchInversionPlan plan) implements Form {}

    private record Domain(FieldInversionBatch source) implements IterationSearch.Domain<Form, Verified> {
        private static final String RULE = "batch-field-unit-inversions";

        @Override public String family() { return "field-inversion-batch"; }
        @Override public String noPlanReason() { return "NO_INVERSION_SAVING"; }
        @Override public int maximumDepth() { return 1; }
        @Override public boolean finished(Form state) { return state instanceof Planned; }

        @Override public IterationSearch.Proposal<Form> propose(Form state, IterationLimits limits,
                BooleanSupplier cancellation) {
            if (!(state instanceof Input)) throw new IllegalStateException("field batch plan is terminal");
            var work = new Work(limits, cancellation, 0);
            try {
                checkLimits(source, limits, work);
                Optional<Form> proposed = FieldBatchInversionPlan.derive(source, work::charge).map(Planned::new);
                return new IterationSearch.Proposal<>(RULE, proposed,
                    proposed.isPresent() ? "CANDIDATE" : noPlanReason(), work.used());
            } catch (Unavailable unavailable) {
                return new IterationSearch.Proposal<>(RULE, Optional.empty(), unavailable.getMessage(), work.used());
            }
        }

        @Override public IterationSearch.Check<Verified> verify(Form from, Form target,
                IterationLimits limits, BooleanSupplier cancellation) {
            if (!(target instanceof Planned proposed)) throw new IllegalArgumentException("unexpected batch target");
            var checked = FieldBatchInversionSearch.verify(source, proposed.plan(), limits, cancellation);
            return new IterationSearch.Check<>(checked.verified().isPresent(), checked.verified(), checked.reason(),
                checked.work());
        }
    }
}
