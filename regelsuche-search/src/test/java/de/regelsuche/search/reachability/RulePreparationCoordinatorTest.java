package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.knowledge.DerivationType;
import de.regelsuche.knowledge.KnowledgePackRegistry;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.knowledge.RuleStatus;
import de.regelsuche.knowledge.SearchEffect;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.SchemaBackedRewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePreparationCoordinatorTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void coordinatesSeveralPrincipalsUnderOneSafePolicy() {
        PatternRewriteRule pythagorean = imported(
            "sympy-trigonometry", "sympy.trig.pythagorean");
        PatternRewriteRule differenceOfSquares = imported(
            "sympy-polynomial", "sympy.poly.factor.diff_squares");
        RulePreparationCoordinator coordinator = coordinator(
            List.of(pythagorean, differenceOfSquares),
            cancellationRules());

        RulePreparationCoordinator.Evaluation first = coordinator.analyze(
            "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
            AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Evaluation second = coordinator.analyze(
            "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2",
            AssumptionSignature.ofExpressions(List.of()));

        assertEquals(first, second);
        assertEquals(0, first.directApplications());
        assertEquals(1, first.preparedApplications());
        assertEquals(1, first.candidates().size());
        RulePreparationCoordinator.Outcome pythagoreanOutcome =
            first.outcome("sympy.trig.pythagorean").orElseThrow();
        assertTrue(pythagoreanOutcome.prepared());
        assertEquals("1", pythagoreanOutcome.candidate()
            .orElseThrow().transformedExpression());
        assertEquals(
            List.of(
                "ast_cancel_division_factor",
                "ast_cancel_division_factor",
                "sympy.trig.pythagorean"),
            pythagoreanOutcome.candidate().orElseThrow()
                .primitiveRuleIds());
        assertEquals(List.of("a != 0", "b != 0"),
            pythagoreanOutcome.candidate().orElseThrow().assumptions());
        assertEquals(
            PatternTargetedLocalBridgeSearch.Status
                .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            first.outcome("sympy.poly.factor.diff_squares")
                .orElseThrow().status());
        assertTrue(coordinator.verify(first).valid());
    }

    @Test
    void explicitSchemaMakesAnAlgorithmicRulePreparablyApplicable() {
        SchemaBackedRewriteRule principal = new SchemaBackedRewriteRule(
            "algorithmic-z-applicability/v1",
            new AlgorithmicZRule(),
            PatternExpr.variable("z"));
        PatternRewriteRule preparation = new PatternRewriteRule(
            "a-to-z",
            PatternExpr.variable("a"),
            PatternExpr.variable("z"));
        RulePreparationCoordinator coordinator = coordinator(
            List.of(principal), List.of(preparation));

        RulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                "a",
                AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome outcome =
            evaluation.outcome("algorithmic-z").orElseThrow();

        assertTrue(outcome.prepared());
        assertTrue(outcome.replayVerified());
        assertEquals("1", outcome.candidate().orElseThrow()
            .transformedExpression());
        assertEquals(
            List.of("a-to-z", "algorithmic-z"),
            outcome.candidate().orElseThrow().primitiveRuleIds());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void directApplicationsRemainTheFirstStage() {
        RulePreparationCoordinator coordinator = coordinator(
            List.of(imported(
                "sympy-trigonometry", "sympy.trig.pythagorean")),
            cancellationRules());

        RulePreparationCoordinator.Evaluation evaluation =
            coordinator.analyze(
                "sin(x)^2 + cos(x)^2",
                AssumptionSignature.ofExpressions(List.of("declared")));

        assertEquals(1, evaluation.directApplications());
        assertEquals(0, evaluation.preparedApplications());
        assertEquals(List.of("declared"),
            evaluation.candidates().getFirst().assumptions());
        assertFalse(evaluation.outcomes().getFirst()
            .bridgeCertificateHash().length() > 0);
    }

    @Test
    void mediumRiskExternalRulesAndDuplicateIdsAreRejected() {
        PatternRewriteRule mediumRisk = new PatternRewriteRule(
            "external-medium",
            PatternExpr.variable("x"),
            PatternExpr.variable("x"),
            new RuleDescriptor(
                "external-medium",
                "external-test-pack",
                "External Test",
                "BSD-3-Clause",
                "1",
                "test fixture",
                DerivationType.REIMPLEMENTED_RULE,
                RuleStatus.VALIDATED,
                "medium",
                List.of("test"),
                List.of(SearchEffect.BRIDGING),
                List.of(),
                List.of()));

        assertThrows(IllegalArgumentException.class,
            () -> coordinator(List.of(mediumRisk), List.of()));
        PatternRewriteRule pythagorean = imported(
            "sympy-trigonometry", "sympy.trig.pythagorean");
        assertThrows(IllegalArgumentException.class,
            () -> coordinator(
                List.of(pythagorean, pythagorean),
                cancellationRules()));
    }

    private static RulePreparationCoordinator coordinator(
        List<? extends PatternRewriteRule> principals,
        List<? extends RewriteRule> preparationRules
    ) {
        return new RulePreparationCoordinator(
            principals,
            preparationRules,
            REVISION,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 128, 128,
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

    private static final class AlgorithmicZRule implements RewriteRule {
        @Override
        public String id() {
            return "algorithmic-z";
        }

        @Override
        public RewriteKind kind() {
            return RewriteKind.SIMPLIFY;
        }

        @Override
        public boolean mayIncreaseComplexity() {
            return false;
        }

        @Override
        public int estimatedCostDelta() {
            return -1;
        }

        @Override
        public boolean isEquivalencePreservingByConstruction() {
            return true;
        }

        @Override
        public boolean matches(Expr subtree) {
            return subtree instanceof VariableExpr variable
                && "z".equals(variable.name());
        }

        @Override
        public Expr apply(Expr subtree) {
            if (!matches(subtree)) {
                throw new IllegalArgumentException(
                    "rule does not match subtree");
            }
            return new NumberExpr(1);
        }
    }
}
