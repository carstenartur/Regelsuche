package de.regelsuche.moves.search;

import static de.regelsuche.moves.search.SearchSpaceAnalyzer.WARNING_DUPLICATE_HEAVY;
import static de.regelsuche.moves.search.SearchSpaceAnalyzer.WARNING_HIGH_BRANCHING_FACTOR;
import static de.regelsuche.moves.search.SearchSpaceAnalyzer.WARNING_SINGLE_DOMINANT_RULE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.SearchSpaceAnalyzer.SearchSpaceReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchSpaceAnalyzerTest {

    private final SearchSpaceAnalyzer analyzer = new SearchSpaceAnalyzer();

    // ---------------------------------------------------------------------------
    // Integration test against real successor generator
    // ---------------------------------------------------------------------------

    @Test
    void reportsSuccessorMetricsForPolynomialExpression() {
        SearchSpaceReport report = analyzer.analyze("x^2 + 6*x + 5");

        assertTrue(report.successorCount() >= 2);
        assertTrue(report.branchingFactor() >= 2d);
        assertTrue(report.uniqueSuccessorCount() >= 2);
        assertTrue(report.successorDistributionByRule().containsKey("COMPLETE_SQUARE"));
        assertTrue(report.successorDistributionByRule().containsKey("FACTOR"));
        assertEquals(
                report.successorCount(),
                report.successorDistributionByRule().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void dominantRuleIsPopulatedForPolynomialExpression() {
        SearchSpaceReport report = analyzer.analyze("x^2 + 6*x + 5");

        assertFalse(report.dominantRule().isEmpty());
        assertTrue(report.dominantRuleShare() > 0d);
        assertTrue(report.dominantRuleShare() <= 1d);
        assertEquals(
                report.successorDistributionByRule().get(report.dominantRule()),
                (int) Math.round(report.dominantRuleShare() * report.successorCount()));
    }

    @Test
    void duplicateMetricsAreConsistentForPolynomialExpression() {
        SearchSpaceReport report = analyzer.analyze("x^2 + 6*x + 5");

        assertEquals(report.successorCount() - report.uniqueSuccessorCount(), report.duplicateSuccessorCount());
        if (report.successorCount() > 0) {
            double expectedRate = (double) report.duplicateSuccessorCount() / report.successorCount();
            assertEquals(expectedRate, report.duplicateRate(), 1e-9);
        } else {
            assertEquals(0.0, report.duplicateRate());
        }
    }

    // ---------------------------------------------------------------------------
    // Controlled-input unit tests via package-private supplier constructor
    // ---------------------------------------------------------------------------

    /** Builds minimal SearchSuccessorStates with the given (successorExpression, moveKind) pairs. */
    private static List<SearchSuccessorGenerator.SearchSuccessorState> states(String[][] pairs) {
        return java.util.Arrays.stream(pairs)
                .map(p -> new SearchSuccessorGenerator.SearchSuccessorState(
                        "src", p[0], null, "enum", p[1], null, null))
                .toList();
    }

    @Test
    void duplicateSuccessorCountIsNumberOfNonUniqueExpressions() {
        // 3 successors, 2 distinct expressions → 1 duplicate
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{{"e1", "RULE_A"}, {"e2", "RULE_A"}, {"e1", "RULE_A"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertEquals(3, report.successorCount());
        assertEquals(2, report.uniqueSuccessorCount());
        assertEquals(1, report.duplicateSuccessorCount());
    }

    @Test
    void duplicateRateIsRatioOfDuplicatesToTotal() {
        // 4 successors, 2 distinct → duplicateRate = 0.5
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{{"e1", "RULE_A"}, {"e1", "RULE_A"}, {"e2", "RULE_A"}, {"e2", "RULE_A"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertEquals(2, report.duplicateSuccessorCount());
        assertEquals(0.5, report.duplicateRate(), 1e-9);
    }

    @Test
    void duplicateRateIsZeroWhenNoSuccessors() {
        var controlled = new SearchSpaceAnalyzer(__ -> List.of());

        SearchSpaceReport report = controlled.analyze("any");

        assertEquals(0, report.duplicateSuccessorCount());
        assertEquals(0.0, report.duplicateRate());
    }

    @Test
    void dominantRuleIsRuleWithMostSuccessors() {
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{
                        {"e1", "RULE_A"}, {"e2", "RULE_A"}, {"e3", "RULE_A"}, {"e4", "RULE_B"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertEquals("RULE_A", report.dominantRule());
    }

    @Test
    void dominantRuleShareIsProportionOfDominantRule() {
        // RULE_A: 3, RULE_B: 1 → share = 0.75
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{
                        {"e1", "RULE_A"}, {"e2", "RULE_A"}, {"e3", "RULE_A"}, {"e4", "RULE_B"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertEquals(0.75, report.dominantRuleShare(), 1e-9);
    }

    @Test
    void dominantRuleIsEmptyWhenNoSuccessors() {
        var controlled = new SearchSpaceAnalyzer(__ -> List.of());

        SearchSpaceReport report = controlled.analyze("any");

        assertTrue(report.dominantRule().isEmpty());
        assertEquals(0.0, report.dominantRuleShare());
    }

    // ---------------------------------------------------------------------------
    // Warnings tests
    // ---------------------------------------------------------------------------

    @Test
    void warningHighBranchingFactorEmittedWhenSuccessorCountExceedsThreshold() {
        String[][] manyPairs = new String[SearchSpaceAnalyzer.HIGH_BRANCHING_FACTOR_THRESHOLD + 1][2];
        for (int i = 0; i < manyPairs.length; i++) {
            manyPairs[i] = new String[]{"e" + i, "RULE_A"};
        }
        var controlled = new SearchSpaceAnalyzer(__ -> states(manyPairs));

        SearchSpaceReport report = controlled.analyze("any");

        assertTrue(report.warnings().contains(WARNING_HIGH_BRANCHING_FACTOR));
    }

    @Test
    void warningHighBranchingFactorNotEmittedForSmallSuccessorSet() {
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{{"e1", "RULE_A"}, {"e2", "RULE_A"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertFalse(report.warnings().contains(WARNING_HIGH_BRANCHING_FACTOR));
    }

    @Test
    void warningDuplicateHeavyEmittedWhenMoreThanHalfAreDuplicates() {
        // 4 out of 5 share expression "e1" → uniqueSuccessorCount=2, duplicateSuccessorCount=3, rate=0.6 > 0.5
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{
                        {"e1", "RULE_A"}, {"e1", "RULE_A"}, {"e1", "RULE_A"}, {"e1", "RULE_A"}, {"e2", "RULE_A"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertTrue(report.duplicateRate() > SearchSpaceAnalyzer.DUPLICATE_RATE_THRESHOLD);
        assertTrue(report.warnings().contains(WARNING_DUPLICATE_HEAVY));
    }

    @Test
    void warningDuplicateHeavyNotEmittedWhenDuplicateRateIsAtOrBelowThreshold() {
        // 2 out of 4 are duplicates → duplicateRate = 0.5, not strictly above threshold
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{
                        {"e1", "RULE_A"}, {"e1", "RULE_A"}, {"e2", "RULE_A"}, {"e3", "RULE_A"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertFalse(report.warnings().contains(WARNING_DUPLICATE_HEAVY));
    }

    @Test
    void warningSingleDominantRuleEmittedWhenOneRuleReachesThreshold() {
        // RULE_A: 4 out of 5 → share = 0.8 ≥ threshold
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{
                        {"e1", "RULE_A"}, {"e2", "RULE_A"}, {"e3", "RULE_A"}, {"e4", "RULE_A"}, {"e5", "RULE_B"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertTrue(report.dominantRuleShare() >= SearchSpaceAnalyzer.DOMINANT_RULE_SHARE_THRESHOLD);
        assertTrue(report.warnings().contains(WARNING_SINGLE_DOMINANT_RULE));
    }

    @Test
    void warningSingleDominantRuleNotEmittedWhenRulesAreBalanced() {
        // RULE_A: 2, RULE_B: 2 → share = 0.5 < 0.8
        var controlled = new SearchSpaceAnalyzer(
                __ -> states(new String[][]{
                        {"e1", "RULE_A"}, {"e2", "RULE_A"}, {"e3", "RULE_B"}, {"e4", "RULE_B"}}));

        SearchSpaceReport report = controlled.analyze("any");

        assertFalse(report.warnings().contains(WARNING_SINGLE_DOMINANT_RULE));
    }

    @Test
    void noWarningsEmittedForEmptySuccessorSet() {
        var controlled = new SearchSpaceAnalyzer(__ -> List.of());

        SearchSpaceReport report = controlled.analyze("any");

        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void warningsListIsImmutable() {
        var controlled = new SearchSpaceAnalyzer(__ -> List.of());

        SearchSpaceReport report = controlled.analyze("any");

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> ((java.util.List<String>) report.warnings()).add("X"));
    }
}
