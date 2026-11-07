package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.search.moves.MoveSearch;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** Searches for an independently checked scalar result of a typed finite iteration. */
public final class FiniteIterationSearch {
    private static final String RULE = "telescope-finite-iteration";

    private FiniteIterationSearch() {}

    public record CheckedReduction(FiniteIterationReduction.Plan plan, String ruleId,
            long synthesisWork, long verificationWork) {}

    public record Result(Optional<CheckedReduction> verified, String reason, MoveSearch.Metrics metrics) {}

    /** Generation, proof, and frontier bookkeeping share the supplied work allowance. */
    public static Result search(FiniteIteration source, IterationLimits limits, int maximumStates,
            BooleanSupplier cancellation) {
        var result = IterationSearch.search(new Source(Objects.requireNonNull(source)), Telescoping.INSTANCE,
            limits, maximumStates, cancellation);
        var verified = result.verified().map(plan -> {
            var step = result.witness().getLast();
            return new CheckedReduction(plan, step.ruleId(), step.synthesisWork(), step.verificationWork());
        });
        return new Result(verified, result.reason(), result.metrics());
    }

    private sealed interface Form permits Source, Reduced {}
    private record Source(FiniteIteration iteration) implements Form {}
    private record Reduced(FiniteIterationReduction.Plan plan) implements Form {}

    private enum Telescoping implements IterationSearch.Domain<Form, FiniteIterationReduction.Plan> {
        INSTANCE;

        @Override public String family() { return "finite-iteration"; }
        @Override public String noPlanReason() { return "NO_FINITE_ITERATION_PLAN"; }
        @Override public int maximumDepth() { return 1; }
        @Override public boolean finished(Form form) { return form instanceof Reduced; }

        @Override public IterationSearch.Proposal<Form> propose(Form source, IterationLimits limits, BooleanSupplier cancellation) {
            if (!(source instanceof Source input)) throw new IllegalStateException("scalar result is terminal");
            var proposal = FiniteIterationReduction.synthesize(input.iteration(), limits, cancellation);
            return new IterationSearch.Proposal<>(RULE, proposal.plan().map(Reduced::new), proposal.reason(), proposal.work());
        }

        @Override public IterationSearch.Check<FiniteIterationReduction.Plan> verify(
                Form source, Form target, IterationLimits limits, BooleanSupplier cancellation) {
            if (!(source instanceof Source input) || !(target instanceof Reduced output)) {
                throw new IllegalArgumentException("unexpected finite iteration proposal");
            }
            var candidate = output.plan();
            var check = FiniteIterationReduction.verify(input.iteration(), candidate.result(), candidate.certificate(),
                limits, cancellation);
            var checked = check.verified()
                ? Optional.of(new FiniteIterationReduction.Plan(candidate.result(), candidate.certificate(), check.cost()))
                : Optional.<FiniteIterationReduction.Plan>empty();
            return new IterationSearch.Check<>(check.verified(), checked, check.reason(), check.work());
        }
    }
}
