package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AcNormalizationPreparationTransformationEngineTest {
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void exposesBuriedFactorAsCompositeTargetFreeMove() {
        AcNormalizationPreparationTransformationEngine engine =
            new AcNormalizationPreparationTransformationEngine();

        AcNormalizationPreparationTransformationEngine.Execution execution =
            engine.transformWithEvidence("(b * (a * c)) / a");
        Transformation candidate = execution.transformations().stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(AcNormalizationPreparationSolver.PREPARATION_RULE_ID))
            .findFirst()
            .orElseThrow();

        assertEquals(
            AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID,
            candidate.rule());
        assertEquals(
            canonicalizer.stableHash("b * c"),
            canonicalizer.stableHash(candidate.transformedExpression()));
        assertEquals(List.of("a != 0"), candidate.assumptions());
        assertEquals(
            List.of(
                AcNormalizationPreparationSolver.PREPARATION_RULE_ID,
                AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID),
            candidate.primitiveRuleIds());
        assertEquals(2, candidate.primitiveStepCount());
        assertEquals("core-rule-preparation", candidate.packId());
        assertEquals(1, execution.observations().size());
        assertEquals(
            AcNormalizationPreparationSolver.Status.PREPARED,
            execution.observations().getFirst().attempt().status());
        assertTrue(execution.observations().getFirst()
            .application().isPresent());
    }

    @Test
    void preparesANestedBinaryAstPosition() {
        AcNormalizationPreparationTransformationEngine engine =
            new AcNormalizationPreparationTransformationEngine();

        boolean reached = engine.transform(
                "1 + (b * (a * c)) / a")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(AcNormalizationPreparationSolver.PREPARATION_RULE_ID))
            .anyMatch(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash("1 + b * c")));

        assertTrue(reached);
    }

    @Test
    void preparesAFunctionArgumentAtItsExactPosition() {
        AcNormalizationPreparationTransformationEngine engine =
            new AcNormalizationPreparationTransformationEngine();

        boolean reached = engine.transform(
                "sin((b * (a * c)) / a)")
            .stream()
            .filter(transformation -> transformation.primitiveRuleIds()
                .contains(AcNormalizationPreparationSolver.PREPARATION_RULE_ID))
            .anyMatch(transformation -> canonicalizer.stableHash(
                    transformation.transformedExpression())
                .equals(canonicalizer.stableHash("sin(b * c)")));

        assertTrue(reached);
    }

    @Test
    void retainsTheExistingExactPolynomialPreparationPath() {
        AcNormalizationPreparationTransformationEngine engine =
            new AcNormalizationPreparationTransformationEngine();

        assertTrue(engine.transform("(x^3 - 1) / (x - 1)")
            .stream()
            .anyMatch(transformation -> transformation.primitiveRuleIds()
                .contains(RulePreparationPlanner.PREPARATION_RULE_ID)));
    }

    @Test
    void disabledAcPreparationReturnsBaseResultsUnchanged() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        TransformationEngine base =
            new RulePreparationTransformationEngine(rules);
        TransformationEngine principal =
            new AstRewriteTransformationEngine(rules);
        AcNormalizationPreparationTransformationEngine disabled =
            new AcNormalizationPreparationTransformationEngine(
                base,
                principal,
                rules.stream().map(RewriteRule::id)
                    .collect(java.util.stream.Collectors.toSet()),
                new AcNormalizationPreparationSolver(),
                0);
        String expression = "(b * (a * c)) / a";

        assertEquals(
            base.transform(expression),
            disabled.transform(expression));
    }

    @Test
    void hiddenPrincipalRulePreventsAcPreparation() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        TransformationEngine base =
            new RulePreparationTransformationEngine(rules);
        AcNormalizationPreparationTransformationEngine engine =
            new AcNormalizationPreparationTransformationEngine(
                base,
                new AstRewriteTransformationEngine(rules),
                Set.of(),
                new AcNormalizationPreparationSolver(),
                16);
        String expression = "(b * (a * c)) / a";

        assertEquals(
            base.transform(expression),
            engine.transform(expression));
    }

    @Test
    void preparedCandidateRequiresReplayThroughVisiblePrincipalRule() {
        List<RewriteRule> rules = AstRewriteTransformationEngine.defaultRules();
        TransformationEngine base =
            new RulePreparationTransformationEngine(rules);
        TransformationEngine emptyPrincipal = expression -> List.of();
        AcNormalizationPreparationTransformationEngine engine =
            new AcNormalizationPreparationTransformationEngine(
                base,
                emptyPrincipal,
                Set.of(AcNormalizationPreparationSolver.PRINCIPAL_RULE_ID),
                new AcNormalizationPreparationSolver(),
                16);

        assertTrue(engine.transform("(b * (a * c)) / a")
            .stream()
            .noneMatch(transformation -> transformation.primitiveRuleIds()
                .contains(AcNormalizationPreparationSolver.PREPARATION_RULE_ID)));
    }

    @Test
    void missingFactorNeverProducesPreparedMove() {
        AcNormalizationPreparationTransformationEngine engine =
            new AcNormalizationPreparationTransformationEngine();

        assertTrue(engine.transform("(b * c) / a")
            .stream()
            .noneMatch(transformation -> transformation.primitiveRuleIds()
                .contains(AcNormalizationPreparationSolver.PREPARATION_RULE_ID)));
    }
}
