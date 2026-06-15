package de.regelsuche.moves.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.SearchSpaceAnalyzer.SearchSpaceReport;
import org.junit.jupiter.api.Test;

class SearchSpaceAnalyzerTest {

    private final SearchSpaceAnalyzer analyzer = new SearchSpaceAnalyzer();

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
}
