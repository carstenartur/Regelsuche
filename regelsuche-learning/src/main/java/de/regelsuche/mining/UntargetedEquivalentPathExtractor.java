package de.regelsuche.mining;

import de.regelsuche.equivalence.EquivalenceService;
import de.regelsuche.equivalence.SymPyEquivalenceService;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalSearchResult;
import de.regelsuche.search.strategy.BestFirstSearchStrategy.GoalStatus;
import de.regelsuche.search.strategy.SearchState;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts exact-equivalence states from a genuinely untargeted search into
 * replay paths for rule mining.
 *
 * <p>The extractor deliberately does not require an immediate score
 * improvement. Representation bridges such as completing a square can make an
 * expression larger before they expose a later useful rule. It remains
 * fail-closed on target leakage, non-equivalence-preserving traces, malformed
 * paths and failed symbolic equivalence.</p>
 */
public final class UntargetedEquivalentPathExtractor {
    private final EquivalenceService equivalenceService;

    public UntargetedEquivalentPathExtractor() {
        this(new SymPyEquivalenceService());
    }

    public UntargetedEquivalentPathExtractor(
        EquivalenceService equivalenceService
    ) {
        this.equivalenceService = Objects.requireNonNull(
            equivalenceService,
            "equivalenceService");
    }

    public List<SuccessfulTransformationPath> extract(
        GoalSearchResult result
    ) {
        Objects.requireNonNull(result, "result");
        if (result.status() != GoalStatus.UNTARGETED) {
            throw new IllegalArgumentException(
                "learning-path extraction requires GoalStatus.UNTARGETED");
        }
        SearchState root = result.states().stream()
            .filter(state -> state.depth() == 0)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "untargeted result has no root state"));

        Map<String, SuccessfulTransformationPath> retained =
            new LinkedHashMap<>();
        result.states().stream()
            .filter(state -> state.depth() > 0)
            .sorted(Comparator.comparingInt(SearchState::depth)
                .thenComparing(SearchState::expression))
            .filter(state -> validTrace(root, state))
            .filter(state -> equivalenceService.areEquivalent(
                root.expression(), state.expression()))
            .map(state -> toLearningPath(root, state))
            .forEach(path -> retained.putIfAbsent(path.id(), path));
        return List.copyOf(retained.values());
    }

    private boolean validTrace(SearchState root, SearchState state) {
        return state.path().size() == state.depth() + 1
            && !state.path().isEmpty()
            && state.path().getFirst().equals(root.expression())
            && state.path().getLast().equals(state.expression())
            && state.appliedRuleIds().size() == state.depth()
            && state.equivalencePreservingFlags().size() == state.depth()
            && state.equivalencePreservingFlags().stream()
                .allMatch(Boolean.TRUE::equals);
    }

    private SuccessfulTransformationPath toLearningPath(
        SearchState root,
        SearchState state
    ) {
        String evidence = equivalenceService.evidence(
            root.expression(),
            state.expression());
        return new SuccessfulTransformationPath(
            null,
            root.expression(),
            state.expression(),
            state.path(),
            state.appliedRuleIds(),
            root.score(),
            state.score(),
            true,
            "untargeted-symbolic-equivalence: " + evidence,
            Map.of(
                "source", "untargeted-search",
                "terminalStatus", GoalStatus.UNTARGETED.name()),
            state.assumptions());
    }
}
