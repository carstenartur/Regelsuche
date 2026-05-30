package de.regelsuche.transform;

import java.util.List;
import java.util.function.IntFunction;

/** Central registry for stable hypothesis operator ids and deterministic operator selection. */
public final class HypothesisOperatorRegistry {
    private final List<Entry> entries;

    public HypothesisOperatorRegistry() {
        this(List.of(
            new Entry(DifferenceOfSquaresPreparationOperator.RULE_ID, DifferenceOfSquaresPreparationOperator::new),
            new Entry(CompleteSquareHypothesisOperator.RULE_ID, CompleteSquareHypothesisOperator::new)
        ));
    }

    public HypothesisOperatorRegistry(List<Entry> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
        long uniqueIds = this.entries.stream().map(Entry::id).distinct().count();
        if (uniqueIds != this.entries.size()) {
            throw new IllegalArgumentException("hypothesis operator ids must be unique");
        }
    }

    public List<String> stableIds() {
        return entries.stream().map(Entry::id).toList();
    }

    public List<String> selectedStableIds(DiscoveryOptions options) {
        if (!hypothesisOperatorsEnabled(options)) {
            return List.of();
        }
        return stableIds();
    }

    public List<HypothesisOperator> selectOperators(DiscoveryOptions options) {
        if (!hypothesisOperatorsEnabled(options)) {
            return List.of();
        }
        int maxCandidates = options == null
            ? DiscoveryOptions.DEFAULT_MAX_HYPOTHESIS_CANDIDATES_PER_OPERATOR
            : options.maxHypothesisCandidatesPerOperator();
        return entries.stream().map(entry -> entry.factory().apply(maxCandidates)).toList();
    }

    private boolean hypothesisOperatorsEnabled(DiscoveryOptions options) {
        if (options == null) {
            return false;
        }
        return options.enableHypothesisOperators()
            && (options.profile() == DiscoveryProfile.HYPOTHESIS_ONLY || options.profile() == DiscoveryProfile.FULL_DISCOVERY);
    }

    public record Entry(String id, IntFunction<HypothesisOperator> factory) {
        public Entry {
            if (id == null || id.isBlank() || factory == null) {
                throw new IllegalArgumentException("id and factory are required");
            }
        }
    }
}
