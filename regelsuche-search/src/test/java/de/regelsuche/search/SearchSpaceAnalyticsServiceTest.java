package de.regelsuche.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SearchSpaceAnalyticsServiceTest {
    @Test
    void computesSearchSpaceMetrics() {
        List<ProofStep> transitions = List.of(
                new ProofStep("A", "B", "rule.one"),
                new ProofStep("A", "C", "macro.fast"),
                new ProofStep("B", "D", "rule.two"),
                new ProofStep("C", "D", "rule.three"));

        SearchSpaceAnalytics analytics = new SearchSpaceAnalyticsService().analyze(transitions);

        assertEquals(4, analytics.statesExplored());
        assertEquals(4, analytics.uniqueCanonicalStates());
        assertEquals(1, analytics.convergentStates());
        assertEquals(1, analytics.learnedMacroUsage());
        assertTrue(analytics.averageBranchingFactor() > 1.0d);
    }

    @Test
    void exportsHighlightedSearchGraph() {
        String dot = new SearchSpaceGraphExporter().toDot(
                List.of(new ProofStep("A", "B", "macro.fast")),
                new SearchGraphStyle(Set.of("A"), Set.of("B"), Set.of(), Set.of()));

        assertTrue(dot.contains("#c8f7c5"));
        assertTrue(dot.contains("#c7d7ff"));
        assertTrue(dot.contains("macro.fast"));
    }

    @Test
    void rendersMacroImpactReport() {
        MacroImpactReport report = new MacroImpactReport(
                new SearchSpaceAnalytics(54, 40, 3, 0, 2.8d),
                new SearchSpaceAnalytics(7, 7, 1, 4, 1.2d));

        assertEquals(47, report.stateReduction());
        assertTrue(report.renderMarkdown().contains("Without macro"));
    }
}
