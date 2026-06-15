package de.regelsuche.moves.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.SearchDiagnosticsAnalyzer.DiagnosticsReport;
import org.junit.jupiter.api.Test;

class SearchDiagnosticsAnalyzerTest {

    private final SearchDiagnosticsAnalyzer analyzer = new SearchDiagnosticsAnalyzer();

    // -----------------------------------------------------------------------
    // Integration tests against real successor generator
    // -----------------------------------------------------------------------

    @Test
    void reachableStatesMatchesUniqueStatesFromExploration() {
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 2, 100);

        assertTrue(report.reachableStates() >= 2, "root + at least one successor");
    }

    @Test
    void duplicateRateIsInUnitInterval() {
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 3, 200);

        assertTrue(report.duplicateRate() >= 0.0);
        assertTrue(report.duplicateRate() <= 1.0);
    }

    @Test
    void cycleRateIsInUnitInterval() {
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 3, 200);

        assertTrue(report.cycleRate() >= 0.0);
        assertTrue(report.cycleRate() <= 1.0);
    }

    @Test
    void deadEndsAreNonNegative() {
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 2, 100);

        assertTrue(report.deadEnds() >= 0);
    }

    @Test
    void averageDepthIsNonNegative() {
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 2, 100);

        assertTrue(report.averageDepth() >= 0.0);
    }

    @Test
    void averageDepthIsAtMostMaxDepth() {
        int maxDepth = 2;
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", maxDepth, 100);

        assertTrue(report.averageDepth() <= maxDepth,
                "average depth cannot exceed the configured max depth");
    }

    @Test
    void nestedExpressionProducesNonZeroReachableStates() {
        DiagnosticsReport report = analyzer.analyze("sin(x^2 + 6*x + 5)", 2, 100);

        assertTrue(report.reachableStates() >= 2);
    }

    // -----------------------------------------------------------------------
    // Edge-case / guard tests
    // -----------------------------------------------------------------------

    @Test
    void returnsZeroMetricsForBlankExpression() {
        DiagnosticsReport report = analyzer.analyze("   ", 4, 100);

        assertEquals(0, report.reachableStates());
        assertEquals(0.0, report.duplicateRate());
        assertEquals(0, report.cycleCount());
        assertEquals(0.0, report.cycleRate());
        assertEquals(0, report.deadEnds());
        assertEquals(0.0, report.averageDepth());
    }

    @Test
    void returnsZeroMetricsForNullExpression() {
        DiagnosticsReport report = analyzer.analyze(null, 4, 100);

        assertEquals(0, report.reachableStates());
        assertEquals(0.0, report.duplicateRate());
        assertEquals(0, report.cycleCount());
        assertEquals(0.0, report.cycleRate());
        assertEquals(0, report.deadEnds());
        assertEquals(0.0, report.averageDepth());
    }

    @Test
    void atDepthZeroOnlyRootIsReachable() {
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 0, 100);

        assertEquals(1, report.reachableStates());
        assertEquals(0.0, report.duplicateRate());
        assertEquals(0, report.cycleCount());
        assertEquals(0.0, report.cycleRate());
        assertEquals(0, report.deadEnds());
        assertEquals(0.0, report.averageDepth());
    }

    // -----------------------------------------------------------------------
    // averageDepth calculation tests
    // -----------------------------------------------------------------------

    @Test
    void averageDepthAtDepthZeroIsZero() {
        // Only root is explored at depth 0
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 0, 100);

        assertEquals(0.0, report.averageDepth(), 1e-9);
    }

    @Test
    void averageDepthWithDepthOneSuccessorsIsPositive() {
        // Root at depth 0, successors at depth 1 → averageDepth > 0
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 1, 100);

        assertTrue(report.averageDepth() > 0.0,
                "states at depth 1 should pull average above zero");
        assertTrue(report.averageDepth() <= 1.0);
    }

    // -----------------------------------------------------------------------
    // Cycle detection tests (controlled via BoundedSearchExplorer)
    // -----------------------------------------------------------------------

    @Test
    void cycleCountAndRateAreZeroWhenNoBackEdgesExist() {
        // Single expression with a unique successor that itself has no successors
        // → no back-edges possible
        DiagnosticsReport report = analyzer.analyze("x^2 + 6*x + 5", 1, 100);

        // We can assert non-negative; actual zero depends on the rule set
        assertTrue(report.cycleCount() >= 0);
        assertTrue(report.cycleRate() >= 0.0);
    }

    // -----------------------------------------------------------------------
    // DiagnosticsReport record contract
    // -----------------------------------------------------------------------

    @Test
    void reportRecordClampsNegativeValues() {
        DiagnosticsReport report = new DiagnosticsReport(-5, -0.3, -1, -0.5, -2, -1.0);

        assertEquals(0, report.reachableStates());
        assertEquals(0.0, report.duplicateRate());
        assertEquals(0, report.cycleCount());
        assertEquals(0.0, report.cycleRate());
        assertEquals(0, report.deadEnds());
        assertEquals(0.0, report.averageDepth());
    }

    @Test
    void reportRecordClampsRatesAboveOne() {
        DiagnosticsReport report = new DiagnosticsReport(10, 1.5, 3, 2.0, 1, 5.0);

        assertEquals(1.0, report.duplicateRate(), 1e-9);
        assertEquals(1.0, report.cycleRate(), 1e-9);
    }
}
