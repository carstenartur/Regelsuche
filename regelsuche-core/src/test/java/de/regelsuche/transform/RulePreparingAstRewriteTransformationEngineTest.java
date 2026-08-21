package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class RulePreparingAstRewriteTransformationEngineTest {
    private static final String CUBIC_SOURCE =
        "(x^3 - 1) / (x - 1)";
    private static final String CUBIC_QUOTIENT =
        "x ^ 2 + x + 1";

    @Test
    void createsTargetFreeCompositeMoveFromPreparationAndCancellation() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        TransformationEngine direct =
            new PreparedAstRewriteTransformationEngine(rules);
        assertFalse(direct.transform(CUBIC_SOURCE).stream()
            .anyMatch(candidate -> CUBIC_QUOTIENT.equals(
                candidate.transformedExpression())));

        RulePreparingAstRewriteTransformationEngine engine =
            new RulePreparingAstRewriteTransformationEngine(rules);
        RulePreparingAstRewriteTransformationEngine.Execution execution =
            engine.transformWithEvidence(CUBIC_SOURCE);

        Transformation prepared = execution.transformations().stream()
            .filter(candidate -> CUBIC_QUOTIENT.equals(
                candidate.transformedExpression()))
            .findFirst()
            .orElseThrow();
        assertEquals(
            RulePreparationPlanner.CANCELLATION_RULE_ID,
            prepared.rule()
        );
        assertEquals(
            List.of(
                RulePreparationPlanner.EXACT_POLYNOMIAL_FACTOR_STEP,
                RulePreparationPlanner.CANCELLATION_RULE_ID
            ),
            prepared.primitiveRuleIds()
        );
        assertEquals(2, prepared.primitiveStepCount());
        assertEquals(List.of("x - 1 != 0"), prepared.assumptions());
        assertTrue(prepared.equivalencePreservingByConstruction());
        assertTrue(prepared.applicationKey().startsWith("prepared:$:"));

        assertEquals(1, execution.preparedOccurrences().size());
        var evidence = execution.preparedOccurrences().getFirst();
        assertEquals("$", evidence.pathKey());
        assertEquals(CUBIC_QUOTIENT, evidence.expressionAfter());
        assertTrue(evidence.application().certificate().verify());
        assertEquals(1, execution.preparationWork().consumedSolverAttempts());
        assertEquals(
            1,
            execution.preparationWork().consumedPreparedApplications()
        );
    }

    @Test
    void disabledPlannerPreservesOrderedDirectRewriteSemantics() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        TransformationEngine direct =
            new PreparedAstRewriteTransformationEngine(rules);
        TransformationEngine disabledPreparation =
            new RulePreparingAstRewriteTransformationEngine(
                rules,
                RulePreparationPlanner.disabled()
            );

        for (String expression : List.of(
            "(x + 0) * 1",
            "a * (b + c)",
            "x^2 - y^2",
            CUBIC_SOURCE
        )) {
            assertEquals(
                direct.transform(expression),
                disabledPreparation.transform(expression),
                expression
            );
        }
    }

    @Test
    void directCancellationRemainsTheCheapFirstPath() {
        RulePreparingAstRewriteTransformationEngine engine =
            new RulePreparingAstRewriteTransformationEngine();

        RulePreparingAstRewriteTransformationEngine.Execution execution =
            engine.transformWithEvidence(
                "((x - 1) * (x + 1)) / (x - 1)"
            );

        Transformation cancellation = execution.transformations().stream()
            .filter(candidate -> "x + 1".equals(
                candidate.transformedExpression()))
            .findFirst()
            .orElseThrow();
        assertEquals(1, cancellation.primitiveStepCount());
        assertTrue(execution.preparedOccurrences().isEmpty());
        assertEquals(0, execution.preparationWork().consumedSolverAttempts());
    }

    @Test
    void nonExactDivisionProducesNoPreparedCompositeMove() {
        RulePreparingAstRewriteTransformationEngine engine =
            new RulePreparingAstRewriteTransformationEngine();

        RulePreparingAstRewriteTransformationEngine.Execution execution =
            engine.transformWithEvidence("(x^3 + 1) / (x - 1)");

        assertTrue(execution.preparedOccurrences().isEmpty());
        assertFalse(execution.transformations().stream()
            .anyMatch(candidate -> candidate.primitiveRuleIds().contains(
                RulePreparationPlanner.EXACT_POLYNOMIAL_FACTOR_STEP)));
        assertEquals(1, execution.preparationWork().consumedSolverAttempts());
    }
}
