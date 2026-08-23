package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.PerfectSquareStructurePreparationSolver;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnifiedRulePreparationCoordinatorTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private final ExpressionCanonicalizer canonicalizer =
        new ExpressionCanonicalizer();

    @Test
    void choosesCertifiedExactPreparationBeforeTheLocalFallback() {
        PatternRewriteRule principal = imported(
            "sympy-polynomial",
            "sympy.poly.factor.diff_squares");
        UnifiedRulePreparationCoordinator coordinator = coordinator(
            principal,
            List.of());

        UnifiedRulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                "4 * x^4 * y^2 - 9 * z^2",
                AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome outcome = evaluation.outcome(
            principal.id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertEquals(
            "EXACT_REGISTRY_PREPARATION_REPLAYED",
            outcome.detailCode());
        assertTrue(outcome.candidate().orElseThrow().primitiveRuleIds()
            .contains(
                PerfectSquareStructurePreparationSolver.PREPARATION_RULE_ID));
        assertEquals(
            canonicalizer.stableHash(
                "(2 * x^2 * y - 3 * z) * (2 * x^2 * y + 3 * z)"),
            canonicalizer.stableHash(outcome.candidate().orElseThrow()
                .transformedExpression()));
        assertTrue(coordinator.verify(evaluation).valid());
        assertTrue(evaluation.aggregateWork().generatedTransitions() > 0);
    }

    @Test
    void retainsThePatternTargetedFallbackWhenNoExactSolverApplies() {
        PatternRewriteRule principal = imported(
            "sympy-trigonometry",
            "sympy.trig.pythagorean");
        UnifiedRulePreparationCoordinator coordinator = coordinator(
            principal,
            cancellationRules());

        UnifiedRulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
                AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome outcome = evaluation.outcome(
            principal.id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertNotEquals(
            "EXACT_REGISTRY_PREPARATION_REPLAYED",
            outcome.detailCode());
        assertEquals(
            List.of(
                "ast_cancel_division_factor",
                "ast_cancel_division_factor",
                "sympy.trig.pythagorean"),
            outcome.candidate().orElseThrow().primitiveRuleIds());
        assertEquals(List.of("a != 0", "b != 0"),
            outcome.candidate().orElseThrow().assumptions());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void bindsExactRegistryIdentityIntoTheCoordinatorConfiguration() {
        PatternRewriteRule principal = imported(
            "sympy-polynomial",
            "sympy.poly.factor.diff_squares");
        UnifiedRulePreparationCoordinator coordinator = coordinator(
            principal,
            List.of());

        assertTrue(coordinator.exactRegistryFingerprint()
            .matches("sha256:[0-9a-f]{64}"));
        assertTrue(coordinator.preparationInventoryFingerprint()
            .matches("sha256:[0-9a-f]{64}"));
    }

    private static UnifiedRulePreparationCoordinator coordinator(
        PatternRewriteRule principal,
        List<? extends RewriteRule> preparationRules
    ) {
        return new UnifiedRulePreparationCoordinator(
            List.of(RewriteApplicabilitySchema.fromPatternRule(principal)),
            preparationRules,
            REVISION,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128,
                32, 5_000, 2_500));
    }

    private static PatternRewriteRule imported(
        String packId,
        String ruleId
    ) {
        return new KnowledgePackRegistry().allPacks().stream()
            .filter(pack -> packId.equals(pack.packId()))
            .flatMap(pack -> pack.rules().stream())
            .filter(rule -> ruleId.equals(rule.id()))
            .findFirst()
            .orElseThrow();
    }

    private static List<RewriteRule> cancellationRules() {
        return AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> "ast_cancel_division_factor"
                .equals(rule.id()))
            .toList();
    }
}
