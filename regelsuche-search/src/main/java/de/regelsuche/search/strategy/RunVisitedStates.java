package de.regelsuche.search.strategy;

import de.regelsuche.search.memory.PruningDecision;
import de.regelsuche.search.memory.PruningReason;
import de.regelsuche.search.memory.SearchMemory;
import de.regelsuche.transform.RewriteKind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Duplicate admission for a single heuristic search run. Durably stored value
 * observations cannot authorize a cutoff in this controller's frontier.
 */
final class RunVisitedStates {
    private final Set<Object> visited = new HashSet<>();
    private final SearchMemory memory;
    private final Function<SearchState, ?> identity;

    RunVisitedStates(SearchMemory memory) {
        this(memory, VisitKey::of);
    }

    private RunVisitedStates(SearchMemory memory, Function<SearchState, ?> identity) {
        this.memory = memory;
        this.identity = identity;
    }

    static RunVisitedStates withHistoricalCanonicalIdentity(SearchMemory memory) {
        return new RunVisitedStates(memory, state -> state.canonicalHash() + ":"
            + historicalApplicationKey(state));
    }

    static RunVisitedStates withHistoricalAssumptionIdentity(SearchMemory memory) {
        return new RunVisitedStates(memory, state -> state.canonicalHash() + ":"
            + state.assumptionFingerprint() + ":" + historicalApplicationKey(state));
    }

    private static String historicalApplicationKey(SearchState state) {
        return String.join(",", state.appliedRuleApplications().stream().sorted().toList());
    }

    boolean contains(SearchState state) {
        return visited.contains(identity.apply(state));
    }

    boolean admit(SearchState state) {
        boolean admitted = visited.add(identity.apply(state));
        if (memory != null && state.depth() > 0) {
            if (admitted) {
                TranspositionGate.observe(memory, state, state.canonicalHash() + "#" + state.depth(), List.of());
            } else {
                memory.recordDecision(new PruningDecision(
                    state.expression(), state.canonicalHash(), PruningReason.ALREADY_KNOWN_EQUAL));
            }
        }
        return admitted;
    }

    private record VisitKey(
        String expression,
        List<String> assumptions,
        Set<String> applications,
        int depth,
        int expandedSteps,
        RewriteKind lastKind,
        boolean equivalencePreservingPath
    ) {
        private static VisitKey of(SearchState state) {
            return new VisitKey(state.expression(), state.assumptions(), state.appliedRuleApplications(),
                state.depth(), state.expandedStepCount(), state.appliedRuleKind(),
                state.transformations() != null
                    && state.equivalencePreservingFlags().stream().allMatch(Boolean::booleanValue));
        }
    }
}
