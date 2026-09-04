package de.regelsuche.sdk.discovery;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;

/** Named, transparent starting budgets for SDK examples and small experiments. */
public final class DiscoveryBudgets {
    private DiscoveryBudgets() {
    }

    /**
     * A small interactive budget.
     *
     * <p>The exact values are part of the API and are retained in run evidence:
     * depth 8, 500 explored states, 5,000 generated successors, 128 successors
     * per state, 128 candidate attempts and 10,000 counterexample attempts.</p>
     */
    public static DiscoveryBudget small() {
        return new DiscoveryBudget(8, 500, 5_000, 128, 128, 10_000);
    }

    /** A deliberately tiny budget useful for budget-exhaustion tests. */
    public static DiscoveryBudget tiny() {
        return new DiscoveryBudget(2, 1, 8, 8, 4, 16);
    }

    /** Creates an explicit budget without hidden defaults. */
    public static DiscoveryBudget of(
            int maxDepth,
            int maxExploredStates,
            int maxGeneratedSuccessors,
            int maxCandidatesPerState,
            int maxCandidateAttempts,
            int maxCounterexampleAttempts
    ) {
        return new DiscoveryBudget(
            maxDepth,
            maxExploredStates,
            maxGeneratedSuccessors,
            maxCandidatesPerState,
            maxCandidateAttempts,
            maxCounterexampleAttempts
        );
    }
}
