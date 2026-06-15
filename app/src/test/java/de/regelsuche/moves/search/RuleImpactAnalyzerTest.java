package de.regelsuche.moves.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.RuleImpactAnalyzer.RuleImpactReport;
import de.regelsuche.moves.search.RuleImpactAnalyzer.RuleStats;
import de.regelsuche.moves.search.SearchSuccessorGenerator.SearchSuccessorState;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleImpactAnalyzerTest {

    private final RuleImpactAnalyzer analyzer = new RuleImpactAnalyzer();

    // -----------------------------------------------------------------------
    // Helper: build minimal SearchSuccessorStates
    // -----------------------------------------------------------------------

    /**
     * Creates a controlled successor state with the given source expression,
     * successor expression, and move kind (rule label).
     */
    private static SearchSuccessorState state(String source, String successor, String moveKind) {
        return new SearchSuccessorState(source, successor, null, "enum", moveKind, null, null);
    }

    // -----------------------------------------------------------------------
    // Integration tests against real successor generator
    // -----------------------------------------------------------------------

    @Test
    void knownRulesAppearInReportForPolynomialExpression() {
        RuleImpactReport report = analyzer.analyze("x^2 + 6*x + 5", 2, 100);

        assertFalse(report.ruleStats().isEmpty(), "at least one rule should be tracked");
        assertTrue(
                report.ruleStats().containsKey("FACTOR") || report.ruleStats().containsKey("COMPLETE_SQUARE"),
                "FACTOR or COMPLETE_SQUARE expected; got: " + report.ruleStats().keySet());
    }

    @Test
    void successorsGeneratedIsPositiveForKnownRules() {
        RuleImpactReport report = analyzer.analyze("x^2 + 6*x + 5", 2, 100);

        for (Map.Entry<String, RuleStats> entry : report.ruleStats().entrySet()) {
            assertTrue(entry.getValue().successorsGenerated() > 0,
                    "successorsGenerated must be positive for rule: " + entry.getKey());
        }
    }

    @Test
    void partitionInvariantHoldsForPolynomialExpression() {
        // successorsGenerated == uniqueStatesAdded + duplicatesGenerated + cyclesGenerated
        RuleImpactReport report = analyzer.analyze("x^2 + 6*x + 5", 3, 200);

        for (Map.Entry<String, RuleStats> entry : report.ruleStats().entrySet()) {
            RuleStats stats = entry.getValue();
            assertEquals(
                    stats.successorsGenerated(),
                    stats.uniqueStatesAdded() + stats.duplicatesGenerated() + stats.cyclesGenerated(),
                    "partition invariant violated for rule: " + entry.getKey());
        }
    }

    @Test
    void nestedExpressionYieldsNonEmptyRuleStats() {
        RuleImpactReport report = analyzer.analyze("sin(x^2 + 6*x + 5)", 2, 100);

        assertFalse(report.ruleStats().isEmpty());
    }

    @Test
    void reportIsEmptyForDepthZero() {
        // No successors are expanded at depth 0
        RuleImpactReport report = analyzer.analyze("x^2 + 6*x + 5", 0, 100);

        assertTrue(report.ruleStats().isEmpty(),
                "no rules should be tracked when maxDepth=0");
    }

    // -----------------------------------------------------------------------
    // Controlled unit tests via package-private Function constructor
    // -----------------------------------------------------------------------

    @Test
    void singleRuleSingleSuccessor() {
        // One expansion: "root" → "child" via RULE_A
        var controlled = new RuleImpactAnalyzer(expr -> {
            if ("root".equals(expr)) return List.of(state("root", "child", "RULE_A"));
            return List.of();
        });

        RuleImpactReport report = controlled.analyze("root", 1, 10);

        assertTrue(report.ruleStats().containsKey("RULE_A"));
        RuleStats stats = report.ruleStats().get("RULE_A");
        assertEquals(1, stats.successorsGenerated());
        assertEquals(1, stats.uniqueStatesAdded());
        assertEquals(0, stats.duplicatesGenerated());
        assertEquals(0, stats.cyclesGenerated());
    }

    @Test
    void duplicateSuccessorsCountedCorrectly() {
        // Two expansions from "root": both via RULE_A.
        // "child1" expands to "shared", then "child2" also produces "shared" → 1 duplicate
        var controlled = new RuleImpactAnalyzer(expr -> switch (expr) {
            case "root" -> Arrays.asList(
                    state("root", "child1", "RULE_A"),
                    state("root", "child2", "RULE_A"));
            case "child1" -> List.of(state("child1", "shared", "RULE_A"));
            case "child2" -> List.of(state("child2", "shared", "RULE_A"));
            default -> List.of();
        });

        RuleImpactReport report = controlled.analyze("root", 2, 50);

        RuleStats stats = report.ruleStats().get("RULE_A");
        assertNotNull(stats);
        // root produces 2 successors (unique); child1/child2 each produce "shared" (1 unique, 1 dup)
        assertEquals(4, stats.successorsGenerated());
        assertEquals(3, stats.uniqueStatesAdded());
        assertEquals(1, stats.duplicatesGenerated());
        assertEquals(0, stats.cyclesGenerated());
    }

    @Test
    void twoDistinctRulesTrackedSeparately() {
        var controlled = new RuleImpactAnalyzer(expr -> {
            if ("root".equals(expr)) {
                return Arrays.asList(
                        state("root", "a", "RULE_A"),
                        state("root", "b", "RULE_B"));
            }
            return List.of();
        });

        RuleImpactReport report = controlled.analyze("root", 1, 10);

        assertTrue(report.ruleStats().containsKey("RULE_A"));
        assertTrue(report.ruleStats().containsKey("RULE_B"));
        assertEquals(1, report.ruleStats().get("RULE_A").successorsGenerated());
        assertEquals(1, report.ruleStats().get("RULE_B").successorsGenerated());
    }

    @Test
    void averageReductionImpactIsPositiveWhenRuleShrinks() {
        // source "xxxx" (length 4) → successor "y" (length 1) → reduction = 3
        var controlled = new RuleImpactAnalyzer(expr -> {
            if ("xxxx".equals(expr)) return List.of(state("xxxx", "y", "SHRINK"));
            return List.of();
        });

        RuleImpactReport report = controlled.analyze("xxxx", 1, 10);

        RuleStats stats = report.ruleStats().get("SHRINK");
        assertNotNull(stats);
        assertEquals(3.0, stats.averageReductionImpact(), 1e-9);
    }

    @Test
    void averageReductionImpactIsNegativeWhenRuleGrows() {
        // source "x" (length 1) → successor "xxxx" (length 4) → reduction = -3
        var controlled = new RuleImpactAnalyzer(expr -> {
            if ("x".equals(expr)) return List.of(state("x", "xxxx", "GROW"));
            return List.of();
        });

        RuleImpactReport report = controlled.analyze("x", 1, 10);

        RuleStats stats = report.ruleStats().get("GROW");
        assertNotNull(stats);
        assertEquals(-3.0, stats.averageReductionImpact(), 1e-9);
    }

    // -----------------------------------------------------------------------
    // Edge-case / guard tests
    // -----------------------------------------------------------------------

    @Test
    void returnsEmptyReportForBlankExpression() {
        RuleImpactReport report = analyzer.analyze("   ", 2, 100);

        assertTrue(report.ruleStats().isEmpty());
    }

    @Test
    void returnsEmptyReportForNullExpression() {
        RuleImpactReport report = analyzer.analyze(null, 2, 100);

        assertTrue(report.ruleStats().isEmpty());
    }

    @Test
    void ruleStatsMapIsImmutable() {
        RuleImpactReport report = analyzer.analyze("x^2 + 6*x + 5", 1, 50);

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> report.ruleStats().put("X", null));
    }

    @Test
    void ruleStatsRecordClampsNegativeValues() {
        RuleStats stats = new RuleStats(-1, -2, -3, -4, 0.0);

        assertEquals(0, stats.successorsGenerated());
        assertEquals(0, stats.duplicatesGenerated());
        assertEquals(0, stats.cyclesGenerated());
        assertEquals(0, stats.uniqueStatesAdded());
    }
}
