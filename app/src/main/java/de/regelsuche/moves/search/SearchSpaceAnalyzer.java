package de.regelsuche.moves.search;

import de.regelsuche.moves.search.SearchSuccessorGenerator.SearchSuccessorState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Computes first-level search-space metrics from direct successors of an expression. */
public final class SearchSpaceAnalyzer {

    /** Warning emitted when the branching factor exceeds {@value #HIGH_BRANCHING_FACTOR_THRESHOLD}. */
    public static final String WARNING_HIGH_BRANCHING_FACTOR = "HIGH_BRANCHING_FACTOR";

    /** Warning emitted when more than half of all successors are duplicates. */
    public static final String WARNING_DUPLICATE_HEAVY = "DUPLICATE_HEAVY";

    /** Warning emitted when a single rule produces {@value #DOMINANT_RULE_SHARE_THRESHOLD} or more of all successors. */
    public static final String WARNING_SINGLE_DOMINANT_RULE = "SINGLE_DOMINANT_RULE";

    static final int HIGH_BRANCHING_FACTOR_THRESHOLD = 10;
    static final double DUPLICATE_RATE_THRESHOLD = 0.5;
    static final double DOMINANT_RULE_SHARE_THRESHOLD = 0.8;

    private final Function<String, List<SearchSuccessorState>> successorSupplier;

    public SearchSpaceAnalyzer() {
        this(new SearchSuccessorGenerator());
    }

    public SearchSpaceAnalyzer(SearchSuccessorGenerator successorGenerator) {
        SearchSuccessorGenerator gen = successorGenerator == null ? new SearchSuccessorGenerator() : successorGenerator;
        this.successorSupplier = gen::generate;
    }

    SearchSpaceAnalyzer(Function<String, List<SearchSuccessorState>> successorSupplier) {
        this.successorSupplier = successorSupplier == null ? __ -> List.of() : successorSupplier;
    }

    public SearchSpaceReport analyze(String expression) {
        List<SearchSuccessorState> successors = successorSupplier.apply(expression);
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
        int duplicateSuccessorCount = successorCount - uniqueSuccessorCount;
        double duplicateRate = successorCount > 0 ? (double) duplicateSuccessorCount / successorCount : 0.0;
        String dominantRule = successorDistributionByRule.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
        double dominantRuleShare = (!dominantRule.isEmpty() && successorCount > 0)
                ? (double) successorDistributionByRule.get(dominantRule) / successorCount
                : 0.0;
        List<String> warnings = buildWarnings(successorCount, duplicateRate, dominantRuleShare);
        return new SearchSpaceReport(
                expression,
                successorCount,
                successorCount,
                uniqueSuccessorCount,
                duplicateSuccessorCount,
                duplicateRate,
                dominantRule,
                dominantRuleShare,
                warnings,
                successorDistributionByRule);
    }

    private static List<String> buildWarnings(
            int successorCount, double duplicateRate, double dominantRuleShare) {
        List<String> warnings = new ArrayList<>();
        if (successorCount > HIGH_BRANCHING_FACTOR_THRESHOLD) {
            warnings.add(WARNING_HIGH_BRANCHING_FACTOR);
        }
        if (duplicateRate > DUPLICATE_RATE_THRESHOLD) {
            warnings.add(WARNING_DUPLICATE_HEAVY);
        }
        if (successorCount > 0 && dominantRuleShare >= DOMINANT_RULE_SHARE_THRESHOLD) {
            warnings.add(WARNING_SINGLE_DOMINANT_RULE);
        }
        return Collections.unmodifiableList(warnings);
    }

    public record SearchSpaceReport(
            String sourceExpression,
            int successorCount,
            double branchingFactor,
            int uniqueSuccessorCount,
            int duplicateSuccessorCount,
            double duplicateRate,
            String dominantRule,
            double dominantRuleShare,
            List<String> warnings,
            Map<String, Integer> successorDistributionByRule) {
        public SearchSpaceReport {
            sourceExpression = sourceExpression == null ? "" : sourceExpression;
            successorCount = Math.max(0, successorCount);
            branchingFactor = Math.max(0d, branchingFactor);
            uniqueSuccessorCount = Math.max(0, uniqueSuccessorCount);
            duplicateSuccessorCount = Math.max(0, duplicateSuccessorCount);
            duplicateRate = Math.max(0d, Math.min(1d, duplicateRate));
            dominantRule = dominantRule == null ? "" : dominantRule;
            dominantRuleShare = Math.max(0d, Math.min(1d, dominantRuleShare));
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            successorDistributionByRule = successorDistributionByRule == null
                    ? Collections.unmodifiableMap(new LinkedHashMap<>())
                    : Collections.unmodifiableMap(new LinkedHashMap<>(successorDistributionByRule));
        }
    }
}
