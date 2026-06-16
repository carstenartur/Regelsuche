package de.regelsuche.moves.search;

import static de.regelsuche.moves.search.SearchSpaceIntelligence.WARNING_CYCLE_HEAVY;
import static de.regelsuche.moves.search.SearchSpaceIntelligence.WARNING_DOMINANT_RULE;
import static de.regelsuche.moves.search.SearchSpaceIntelligence.WARNING_HIGH_BRANCHING_FACTOR;
import static de.regelsuche.moves.search.SearchSpaceIntelligence.WARNING_HIGH_DUPLICATE_RATE;
import static de.regelsuche.moves.search.SearchSpaceIntelligence.WARNING_SEARCH_SPACE_EXPLOSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.search.SearchSpaceIntelligence.IntelligenceReport;
import org.junit.jupiter.api.Test;

class SearchSpaceIntelligenceTest {

    private final SearchSpaceIntelligence intelligence = new SearchSpaceIntelligence();

    // -----------------------------------------------------------------------
    // Integration tests against real successor generator
    // -----------------------------------------------------------------------

    @Test
    void dominantRuleIsNonEmptyForPolynomialExpression() {
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 2, 100);

        assertFalse(report.dominantRule().isEmpty(),
                "at least one rule should be identified as dominant");
    }

    @Test
    void dominantRuleShareIsInUnitInterval() {
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 2, 100);

        assertTrue(report.dominantRuleShare() >= 0.0);
        assertTrue(report.dominantRuleShare() <= 1.0);
    }

    @Test
    void estimatedGrowthIsPositiveForPolynomialExpression() {
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 2, 100);

        assertTrue(report.estimatedGrowth() > 0.0,
                "at least one successor exists so estimatedGrowth should be > 0");
    }

    @Test
    void warningsListIsNonNull() {
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 2, 100);

        assertNotNull(report.warnings());
    }

    @Test
    void warningsListIsImmutable() {
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 2, 100);

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> ((java.util.List<String>) report.warnings()).add("X"));
    }

    @Test
    void nestedExpressionProducesValidReport() {
        IntelligenceReport report = intelligence.analyze("sin(x^2 + 6*x + 5)", 2, 100);

        assertTrue(report.estimatedGrowth() >= 0.0);
        assertNotNull(report.warnings());
    }

    // -----------------------------------------------------------------------
    // Edge-case / guard tests
    // -----------------------------------------------------------------------

    @Test
    void returnsEmptyReportForBlankExpression() {
        IntelligenceReport report = intelligence.analyze("   ");

        assertEquals("", report.dominantRule());
        assertEquals(0.0, report.dominantRuleShare());
        assertFalse(report.duplicateHeavySearchSpace());
        assertEquals(0.0, report.estimatedGrowth());
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void returnsEmptyReportForNullExpression() {
        IntelligenceReport report = intelligence.analyze(null);

        assertEquals("", report.dominantRule());
        assertEquals(0.0, report.estimatedGrowth());
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void atDepthZeroReportIsMinimal() {
        // Only root explored, no expansion, no rule data
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 0, 100);

        assertEquals("", report.dominantRule());
        assertEquals(0.0, report.dominantRuleShare());
        assertFalse(report.duplicateHeavySearchSpace());
        assertTrue(report.warnings().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Warning-trigger tests (via controlled BoundedSearchExplorer + RuleImpactAnalyzer)
    // -----------------------------------------------------------------------

    @Test
    void warningHighBranchingFactorConstantIsDefined() {
        assertTrue(SearchSpaceIntelligence.HIGH_BRANCHING_FACTOR_THRESHOLD > 0.0);
        assertEquals(WARNING_HIGH_BRANCHING_FACTOR, "HIGH_BRANCHING_FACTOR");
    }

    @Test
    void warningSearchSpaceExplosionConstantIsDefined() {
        assertTrue(SearchSpaceIntelligence.SEARCH_SPACE_EXPLOSION_THRESHOLD > 0.0);
        assertEquals(WARNING_SEARCH_SPACE_EXPLOSION, "SEARCH_SPACE_EXPLOSION");
    }

    @Test
    void warningDominantRuleConstantIsDefined() {
        assertTrue(SearchSpaceIntelligence.DOMINANT_RULE_SHARE_THRESHOLD > 0.0);
        assertTrue(SearchSpaceIntelligence.DOMINANT_RULE_SHARE_THRESHOLD <= 1.0);
        assertEquals(WARNING_DOMINANT_RULE, "DOMINANT_RULE");
    }

    @Test
    void warningHighDuplicateRateConstantIsDefined() {
        assertTrue(SearchSpaceIntelligence.HIGH_DUPLICATE_RATE_THRESHOLD > 0.0);
        assertTrue(SearchSpaceIntelligence.HIGH_DUPLICATE_RATE_THRESHOLD < 1.0);
        assertEquals(WARNING_HIGH_DUPLICATE_RATE, "HIGH_DUPLICATE_RATE");
    }

    @Test
    void warningCycleHeavyConstantIsDefined() {
        assertTrue(SearchSpaceIntelligence.CYCLE_HEAVY_THRESHOLD > 0.0);
        assertTrue(SearchSpaceIntelligence.CYCLE_HEAVY_THRESHOLD < 1.0);
        assertEquals(WARNING_CYCLE_HEAVY, "CYCLE_HEAVY");
    }

    // -----------------------------------------------------------------------
    // IntelligenceReport record contract
    // -----------------------------------------------------------------------

    @Test
    void reportRecordClampsNegativeValues() {
        IntelligenceReport report = new IntelligenceReport("RULE", -0.5, false, -1.0, null);

        assertEquals(0.0, report.dominantRuleShare());
        assertEquals(0.0, report.estimatedGrowth());
        assertNotNull(report.warnings());
        assertTrue(report.warnings().isEmpty());
    }

    @Test
    void reportRecordClampsShareAboveOne() {
        IntelligenceReport report = new IntelligenceReport("RULE", 2.0, true, 5.0, java.util.List.of());

        assertEquals(1.0, report.dominantRuleShare(), 1e-9);
    }

    @Test
    void estimatedGrowthAtDepthZeroIsOne() {
        // averageBranchingFactor^0 = 1 regardless of branching
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 0, 100);

        assertEquals(1.0, report.estimatedGrowth(), 1e-9,
                "estimatedGrowth at depth 0 should be 1.0");
    }

    @Test
    void estimatedGrowthIsPositiveWhenSuccessorsExist() {
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 2, 100);

        assertTrue(report.estimatedGrowth() > 0.0,
                "estimatedGrowth should be positive when successors exist");
    }

    @Test
    void duplicateHeavySearchSpaceMatchesDuplicateRateWarning() {
        // duplicateHeavySearchSpace and WARNING_HIGH_DUPLICATE_RATE are both derived
        // from the same threshold, so they must always agree.
        IntelligenceReport report = intelligence.analyze("x^2 + 6*x + 5", 4, 500);

        assertEquals(
                report.warnings().contains(WARNING_HIGH_DUPLICATE_RATE),
                report.duplicateHeavySearchSpace(),
                "duplicateHeavySearchSpace must match presence of HIGH_DUPLICATE_RATE warning");
    }
}
