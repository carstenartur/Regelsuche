package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulePreparationCoordinatorGuardTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void concreteDirectReplayDoesNotDependOnSchemaPrecision() {
        RewriteApplicabilitySchema staleSchema =
            new RewriteApplicabilitySchema(
                "algorithmic-z-stale-schema/v1",
                new AlgorithmicZRule(),
                PatternExpr.variable("y"),
                RecognitionProfile.exact());
        RulePreparationCoordinator coordinator = coordinator(
            List.of(staleSchema), List.of());

        RulePreparationCoordinator.Evaluation evaluation = coordinator.analyze(
            "z", AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome outcome = evaluation
            .outcome("algorithmic-z").orElseThrow();

        assertTrue(outcome.direct());
        assertEquals("1", outcome.candidate().orElseThrow()
            .transformedExpression());
        assertEquals("COORDINATOR_DIRECT_REPLAYED", outcome.detailCode());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void inferredDenominatorGuardsRejectUnknownDirectApplication() {
        RewriteApplicabilitySchema schema =
            RewriteApplicabilitySchema.fromPatternRule(telescopingRule());
        RulePreparationCoordinator coordinator = coordinator(
            List.of(schema), List.of());

        RulePreparationCoordinator.Evaluation missing = coordinator.analyze(
            "1 / (n * (n + 1))",
            AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome missingOutcome = missing
            .outcome("test.telescoping").orElseThrow();

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED,
            missingOutcome.status());
        assertFalse(missingOutcome.candidate().isPresent());
        assertEquals("REQUIRED_ASSUMPTION_UNKNOWN",
            missingOutcome.detailCode());
        assertTrue(coordinator.verify(missing).valid());

        RulePreparationCoordinator.Evaluation declared = coordinator.analyze(
            "1 / (n * (n + 1))",
            AssumptionSignature.ofExpressions(
                List.of("n != 0", "n + 1 != 0")));
        RulePreparationCoordinator.Outcome declaredOutcome = declared
            .outcome("test.telescoping").orElseThrow();

        assertTrue(declaredOutcome.direct());
        assertEquals(List.of("n != 0", "n + 1 != 0"),
            declaredOutcome.candidate().orElseThrow().assumptions());
        assertTrue(coordinator.verify(declared).valid());
    }

    @Test
    void inferredDenominatorGuardsAlsoProtectPreparedApplication() {
        RewriteApplicabilitySchema schema =
            RewriteApplicabilitySchema.fromPatternRule(telescopingRule());
        RulePreparationCoordinator coordinator = coordinator(
            List.of(schema), cancellationRules());
        String source =
            "1 / (((n * a) / a) * (((n + 1) * b) / b))";

        RulePreparationCoordinator.Evaluation missing = coordinator.analyze(
            source, AssumptionSignature.ofExpressions(List.of()));
        RulePreparationCoordinator.Outcome missingOutcome = missing
            .outcome("test.telescoping").orElseThrow();

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED,
            missingOutcome.status());
        assertFalse(missingOutcome.candidate().isPresent());
        assertEquals("REQUIRED_ASSUMPTION_UNKNOWN",
            missingOutcome.detailCode());
        assertTrue(coordinator.verify(missing).valid());

        RulePreparationCoordinator.Evaluation declared = coordinator.analyze(
            source,
            AssumptionSignature.ofExpressions(
                List.of("n != 0", "n + 1 != 0")));
        RulePreparationCoordinator.Outcome declaredOutcome = declared
            .outcome("test.telescoping").orElseThrow();

        assertTrue(declaredOutcome.prepared());
        assertEquals(
            List.of(
                "ast_cancel_division_factor",
                "ast_cancel_division_factor",
                "test.telescoping"),
            declaredOutcome.candidate().orElseThrow().primitiveRuleIds());
        assertEquals(
            List.of("a != 0", "b != 0", "n != 0", "n + 1 != 0"),
            declaredOutcome.candidate().orElseThrow().assumptions());
        assertTrue(coordinator.verify(declared).valid());
    }

    private static RulePreparationCoordinator coordinator(
        List<RewriteApplicabilitySchema> schemas,
        List<? extends RewriteRule> preparationRules
    ) {
        return new RulePreparationCoordinator(
            schemas,
            preparationRules,
            REVISION,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128,
                32, 5_000, 2_500));
    }

    private static PatternRewriteRule telescopingRule() {
        PatternExpr a = PatternExpr.var("A");
        PatternExpr one = PatternExpr.num(1);
        PatternExpr successor = PatternExpr.op(
            de.regelsuche.ast.BinaryOperator.ADD, a, one);
        return new PatternRewriteRule(
            "test.telescoping",
            PatternExpr.op(
                de.regelsuche.ast.BinaryOperator.DIV,
                one,
                PatternExpr.op(
                    de.regelsuche.ast.BinaryOperator.MUL,
                    a,
                    successor)),
            PatternExpr.op(
                de.regelsuche.ast.BinaryOperator.SUB,
                PatternExpr.op(
                    de.regelsuche.ast.BinaryOperator.DIV, one, a),
                PatternExpr.op(
                    de.regelsuche.ast.BinaryOperator.DIV,
                    one,
                    successor)));
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
