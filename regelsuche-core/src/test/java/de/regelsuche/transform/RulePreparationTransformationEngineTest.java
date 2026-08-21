package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RulePreparationTransformationEngineTest {
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void exposesThePreparedCancellationAsACompositeTargetFreeMove() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        Transformation candidate = engine.transform(
                "(x^3 - 1) / (x - 1)")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID))
            .findFirst()
            .orElseThrow();

        assertEquals(
            RulePreparationPlanner.PRINCIPAL_RULE_ID,
            candidate.rule());
        assertEquals(
            canonicalizer.stableHash("x^2 + x + 1"),
            canonicalizer.stableHash(
                candidate.transformedExpression()));
        assertEquals(List.of("x - 1 != 0"), candidate.assumptions());
        assertEquals(
            List.of(
                RulePreparationPlanner.PREPARATION_RULE_ID,
                RulePreparationPlanner.PRINCIPAL_RULE_ID),
            candidate.primitiveRuleIds());
        assertEquals(2, candidate.primitiveStepCount());
    }

    @Test
    void preparesANestedAstNodeRatherThanOnlyTheRoot() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        boolean reached = engine.transform(
                "1 + (x^3 - 1) / (x - 1)")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID))
            .anyMatch(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash(
                    "1 + (x^2 + x + 1)")));

        assertTrue(reached);
    }

    @Test
    void preparesAFunctionArgumentAtItsExactAstPosition() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        boolean reached = engine.transform(
                "sin((x^3 - 1) / (x - 1))")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID))
            .anyMatch(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash(
                    "sin(x^2 + x + 1)")));

        assertTrue(reached);
    }

    @Test
    void disabledPlanningReturnsTheDirectEngineResultsByteForByte() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        RulePreparationTransformationEngine disabled =
            new RulePreparationTransformationEngine(
                direct,
                direct.rules().stream().map(RewriteRule::id)
                    .collect(java.util.stream.Collectors.toSet()),
                new RulePreparationPlanner(),
                0);
        String expression = "(x^3 - 1) / (x - 1)";

        assertEquals(
            direct.transform(expression),
            disabled.transform(expression));
    }

    @Test
    void hiddenPrincipalRulePreventsPreparation() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine(
                direct,
                Set.of(),
                new RulePreparationPlanner(),
                16);

        assertEquals(
            direct.transform("(x^3 - 1) / (x - 1)"),
            engine.transform("(x^3 - 1) / (x - 1)"));
    }

    @Test
    void preparedCandidateRequiresReplayThroughThePrincipalRule() {
        TransformationEngine emptyDirectEngine = expression -> List.of();
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine(
                emptyDirectEngine,
                Set.of(RulePreparationPlanner.PRINCIPAL_RULE_ID),
                new RulePreparationPlanner(),
                16);

        assertTrue(engine.transform("(x^3 - 1) / (x - 1)").isEmpty());
    }

    @Test
    void nonExactDivisionNeverProducesAPreparedMove() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        assertTrue(engine.transform("(x^3 + 1) / (x - 1)")
            .stream()
            .noneMatch(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID)));
    }

    @Test
    void memoizesRepeatedPreparationAnalysisWithinOneInvocation() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        RulePreparationTransformationEngine.Execution execution =
            engine.transformWithEvidence(
                "(x^3 - 1) / (x - 1) + (x^3 - 1) / (x - 1)");
        RulePreparationTransformationEngine.CacheMetrics metrics =
            execution.cacheMetrics();

        assertTrue(metrics.lookups() > 0);
        assertTrue(metrics.hits() > 0);
        assertTrue(metrics.misses() > 0);
        assertTrue(metrics.retainedEntries() > 0);
        assertEquals(metrics.lookups(), metrics.hits() + metrics.misses());
        assertEquals(1, metrics.preparedVerifications());
        assertEquals(0, metrics.skippedUnverifiable());
        assertTrue(metrics.skippedZeroSolverWork() > 0);
        assertTrue(engine.ruleInventoryHash().startsWith("sha256:"));
    }

    @Test
    void distinctAstStructuresDoNotReuseOccurrenceSpecificEvidence() {
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine();

        RulePreparationTransformationEngine.Execution execution =
            engine.transformWithEvidence(
                "(x^3 + x^2 + x + 1) / (x + 1)"
                    + " + (x^3 + (x^2 + (x + 1))) / (x + 1)");

        assertEquals(2, execution.cacheMetrics().preparedVerifications());
        assertEquals(0, execution.cacheMetrics().skippedUnverifiable());
        assertTrue(execution.transformations().stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID))
            .count() >= 2);
    }

    @Test
    void zeroCacheCapacityPreservesResultsWithoutMemoization() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        Set<String> visibleRuleIds = direct.rules().stream()
            .map(RewriteRule::id)
            .collect(java.util.stream.Collectors.toSet());
        RulePreparationTransformationEngine uncached =
            new RulePreparationTransformationEngine(
                direct,
                visibleRuleIds,
                new RulePreparationPlanner(),
                16,
                AssumptionSignature.ofExpressions(List.of()),
                "sha256:test-inventory",
                0);
        String expression =
            "(x^3 - 1) / (x - 1) + (x^3 - 1) / (x - 1)";

        RulePreparationTransformationEngine.Execution execution =
            uncached.transformWithEvidence(expression);

        assertEquals(0, execution.cacheMetrics().hits());
        assertEquals(0, execution.cacheMetrics().retainedEntries());
        assertTrue(execution.cacheMetrics().misses() > 0);
        assertTrue(execution.cacheMetrics().preparedVerifications() >= 2);
        assertTrue(execution.transformations().stream()
            .anyMatch(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID)));
    }

    @Test
    void budgetInconclusiveResultsAreNotMemoized() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        Set<String> visibleRuleIds = direct.rules().stream()
            .map(RewriteRule::id)
            .collect(java.util.stream.Collectors.toSet());
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine(
                direct,
                visibleRuleIds,
                new RulePreparationPlanner(
                    new RulePreparationPlanner.Budget(0)),
                16,
                AssumptionSignature.ofExpressions(List.of()),
                "sha256:test-inventory",
                128);

        RulePreparationTransformationEngine.Execution execution =
            engine.transformWithEvidence(
                "(x^3 - 1) / (x - 1) + (x^3 - 1) / (x - 1)");

        assertTrue(execution.cacheMetrics().skippedInconclusive() >= 2);
        assertEquals(0, execution.cacheMetrics().preparedVerifications());
        assertTrue(execution.transformations().stream()
            .noneMatch(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID)));
    }

    @Test
    void boundedCacheEvictsTheOldestExpensiveAnalysisDeterministically() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        Set<String> visibleRuleIds = direct.rules().stream()
            .map(RewriteRule::id)
            .collect(java.util.stream.Collectors.toSet());
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine(
                direct,
                visibleRuleIds,
                new RulePreparationPlanner(),
                16,
                AssumptionSignature.ofExpressions(List.of()),
                "sha256:test-inventory",
                1);

        RulePreparationTransformationEngine.Execution execution =
            engine.transformWithEvidence(
                "(x^3 - 1) / (x - 1)"
                    + " + (x^4 - 1) / (x - 1)"
                    + " + (x^3 - 1) / (x - 1)");

        assertEquals(1, execution.cacheMetrics().retainedEntries());
        assertTrue(execution.cacheMetrics().evictions() >= 2);
        assertEquals(3, execution.cacheMetrics().preparedVerifications());
    }

    @Test
    void cacheContextRetainsNormalizedAssumptionsAndInventoryIdentity() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        RulePreparationTransformationEngine engine =
            new RulePreparationTransformationEngine(
                rules,
                AssumptionSignature.ofExpressions(
                    List.of("0 != (x - 1)")));
        RulePreparationTransformationEngine sameInventory =
            new RulePreparationTransformationEngine(rules);

        assertEquals(
            "x - 1 != 0",
            engine.assumptionSignature().fingerprint());
        assertEquals(
            sameInventory.ruleInventoryHash(),
            engine.ruleInventoryHash());
    }

    @Test
    void invalidCacheConfigurationFailsClosed() {
        AstRewriteTransformationEngine direct =
            new AstRewriteTransformationEngine();
        Set<String> visibleRuleIds = direct.rules().stream()
            .map(RewriteRule::id)
            .collect(java.util.stream.Collectors.toSet());

        assertThrows(
            IllegalArgumentException.class,
            () -> new RulePreparationTransformationEngine(
                direct,
                visibleRuleIds,
                new RulePreparationPlanner(),
                16,
                AssumptionSignature.ofExpressions(List.of()),
                " ",
                1));
        assertThrows(
            IllegalArgumentException.class,
            () -> new RulePreparationTransformationEngine(
                direct,
                visibleRuleIds,
                new RulePreparationPlanner(),
                16,
                AssumptionSignature.ofExpressions(List.of()),
                "sha256:test-inventory",
                -1));
        assertThrows(
            IllegalArgumentException.class,
            () -> new RulePreparationTransformationEngine.CacheMetrics(
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0));
    }
}
