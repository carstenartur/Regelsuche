package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FieldInversionReduction;
import de.regelsuche.search.moves.MoveSearch;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Unavailable;
import static de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;

/** Proposes and independently checks inversion schedules on the shared move frontier. */
public final class FieldInversionSearch {
    private FieldInversionSearch() {}

    public record CheckedMove(String ruleId, long synthesisWork, long verificationWork) {}

    public record Result(Optional<FieldInversionPlan.Verified> verified, String reason,
            List<CheckedMove> witness, MoveSearch.Metrics metrics) {
        public Result {
            witness = List.copyOf(witness);
        }
    }

    public record Verification(Optional<FieldInversionPlan.Verified> verified, String reason, long work) {}

    public static Result search(FieldInversionReduction source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        var domain = new Domain(Objects.requireNonNull(source));
        var run = IterationSearch.search(new Input(), domain, limits, maximumStates, cancellation);
        return new Result(run.verified(), run.reason(), run.witness().stream()
            .map(step -> new CheckedMove(step.ruleId(), step.synthesisWork(), step.verificationWork())).toList(),
            run.metrics());
    }

    public static Verification verify(FieldInversionReduction source, FieldInversionPlan candidate,
            IterationLimits limits, BooleanSupplier cancellation) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(candidate);
        var work = new Work(limits, cancellation, 0);
        try {
            checkLimits(source, limits, work);
            var verified = FieldInversionPlan.check(source, candidate, work::charge);
            return new Verification(verified, verified.isPresent() ? "VERIFIED" : "INVALID_INVERSION_SCHEDULE", work.used());
        } catch (Unavailable unavailable) {
            return new Verification(Optional.empty(), unavailable.getMessage(), work.used());
        }
    }

    private static void checkLimits(FieldInversionReduction source, IterationLimits limits, Work work) {
        work.charge(1);
        if (source.factors().size() > limits.maximumTerms()) throw new Unavailable("FIELD_FACTOR_LIMIT");
    }

    private sealed interface Form permits Input, Planned {}
    private record Input() implements Form {}
    private record Planned(FieldInversionPlan plan) implements Form {}

    private record Domain(FieldInversionReduction source) implements IterationSearch.Domain<Form, FieldInversionPlan.Verified> {
        @Override public String family() { return "field-inversion-reduction"; }
        @Override public String noPlanReason() { return "NO_INVERSION_SAVING"; }
        @Override public int maximumDepth() { return 1; }
        @Override public boolean finished(Form state) { return state instanceof Planned; }

        @Override public IterationSearch.Proposal<Form> propose(Form state, IterationLimits limits,
                BooleanSupplier cancellation) {
            if (!(state instanceof Input)) throw new IllegalStateException("field inversion plan is terminal");
            String rule = source.kind() == FieldInversionReduction.Kind.PRODUCT
                ? "group-field-product-inversions" : "hoist-invariant-field-inversions";
            var work = new Work(limits, cancellation, 0);
            try {
                checkLimits(source, limits, work);
                Optional<Form> proposed = FieldInversionPlan.derive(source, work::charge).map(Planned::new);
                return new IterationSearch.Proposal<>(rule, proposed,
                    proposed.isPresent() ? "CANDIDATE" : noPlanReason(), work.used());
            } catch (Unavailable unavailable) {
                return new IterationSearch.Proposal<>(rule, Optional.empty(), unavailable.getMessage(), work.used());
            }
        }

        @Override public IterationSearch.Check<FieldInversionPlan.Verified> verify(Form from, Form target,
                IterationLimits limits, BooleanSupplier cancellation) {
            if (!(target instanceof Planned proposed)) throw new IllegalArgumentException("unexpected inversion target");
            var checked = FieldInversionSearch.verify(source, proposed.plan(), limits, cancellation);
            return new IterationSearch.Check<>(checked.verified().isPresent(), checked.verified(), checked.reason(),
                checked.work());
        }
    }
}
