package de.regelsuche.search.moves;

import de.regelsuche.retention.RetainedOperation;
import java.util.Objects;

/** Paid recognition of actual execution paths, independent of mathematical authorization. */
final class NativeExecutionInventory {
    private NativeExecutionInventory() {}

    static boolean eligible(NativeMoveSearch.Problem problem, TypedSourceOnlySearch.Objective objective) {
        Objects.requireNonNull(problem, "problem");
        RetainedOperation.work(1);
        if (problem.policy() != NativeMovePriorityPolicy.INVENTORY_ORDER
                || problem.stateScore() != NativeMoveSearch.ZeroScore.INSTANCE
                || problem.stateValue() != NativeStateValue.NONE
                || objective != null && objective != NativeNodeCountObjective.INSTANCE) return false;
        RetainedOperation.work(1);
        if (!(problem.verifier() instanceof NativeVerifier.Registered registered)) return false;
        for (var provider : problem.providers()) {
            RetainedOperation.work(1);
            if (!registered.coversKnownExecution(provider)) return false;
        }
        return true;
    }
}
