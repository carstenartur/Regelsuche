package de.regelsuche.moves.search;

import de.regelsuche.moves.search.SearchSuccessorGenerator.SearchSuccessorState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Computes first-level search-space metrics from direct successors of an expression. */
public final class SearchSpaceAnalyzer {

    private final SearchSuccessorGenerator successorGenerator;

    public SearchSpaceAnalyzer() {
        this(new SearchSuccessorGenerator());
    }

    public SearchSpaceAnalyzer(SearchSuccessorGenerator successorGenerator) {
        this.successorGenerator = successorGenerator == null ? new SearchSuccessorGenerator() : successorGenerator;
    }

    public SearchSpaceReport analyze(String expression) {
        List<SearchSuccessorState> successors = successorGenerator.generate(expression);
        Map<String, Integer> successorDistributionByRule = new LinkedHashMap<>();
        for (SearchSuccessorState successor : successors) {
            String rule = successor.moveKind().isBlank() ? successor.enumeratorId() : successor.moveKind();
            successorDistributionByRule.merge(rule, 1, Integer::sum);
        }
        int successorCount = successors.size();
        int uniqueSuccessorCount = (int) successors.stream()
                .map(SearchSuccessorState::successorExpression)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return new SearchSpaceReport(
                expression,
                successorCount,
                successorCount,
                uniqueSuccessorCount,
                successorDistributionByRule);
    }

    public record SearchSpaceReport(
            String sourceExpression,
            int successorCount,
            double branchingFactor,
            int uniqueSuccessorCount,
            Map<String, Integer> successorDistributionByRule) {
        public SearchSpaceReport {
            sourceExpression = sourceExpression == null ? "" : sourceExpression;
            successorCount = Math.max(0, successorCount);
            branchingFactor = Math.max(0d, branchingFactor);
            uniqueSuccessorCount = Math.max(0, uniqueSuccessorCount);
            successorDistributionByRule = successorDistributionByRule == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(successorDistributionByRule));
        }
    }
}
