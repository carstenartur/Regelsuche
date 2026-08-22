package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternPreparationPlan.Budget;
import de.regelsuche.transform.PatternPreparationPlan.Certificate;
import de.regelsuche.transform.PatternPreparationPlan.LimitReason;
import de.regelsuche.transform.PatternPreparationPlan.Status;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternTargetedLocalBridgePlannerTest {
    private static final String HIDDEN_PYTHAGOREAN =
        "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2";

    @Test
    void reachesPythagoreanRuleThroughTwoCertifiedCancellations() {
        PatternTargetedLocalBridgePlanner planner = planner(
            Budget.safeDefaults());

        var attempt = planner.plan(HIDDEN_PYTHAGOREAN);

        assertEquals(Status.PREPARED, attempt.status());
        var application = attempt.application().orElseThrow();
        assertEquals("1", application.resultExpression());
        assertEquals(List.of("a != 0", "b != 0"),
            application.finalAssumptions());
        assertEquals(List.of(
            "ast_cancel_division_factor",
            "ast_cancel_division_factor",
            "sympy.trig.pythagorean"),
            application.primitiveRuleIds());
        assertEquals(2, application.preparationSteps().size());
        assertEquals(2, application.work().maximumDepthReached());
        assertTrue(planner.verify(application));
    }

    @Test
    void directMatchConsumesNoPreparationPath() {
        PatternTargetedLocalBridgePlanner planner = planner(
            Budget.safeDefaults());

        var attempt = planner.plan("sin(x)^2 + cos(x)^2");

        assertEquals(Status.DIRECT_MATCH_AVAILABLE, attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertEquals(1, attempt.work().visitedStates());
        assertEquals(0, attempt.work().maximumPrimitivePathWork());
    }

    @Test
    void differentArgumentsDoNotCreateFalsePythagoreanBridge() {
        PatternTargetedLocalBridgePlanner planner = planner(
            Budget.safeDefaults());

        var attempt = planner.plan("sin(x)^2 + cos(y)^2");

        assertEquals(
            Status.NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            attempt.status());
        assertTrue(attempt.application().isEmpty());
        assertTrue(attempt.reachedLimits().isEmpty());
    }

    @Test
    void insufficientDepthIsVisibleAndInconclusive() {
        PatternTargetedLocalBridgePlanner planner = planner(
            new Budget(1, 256, 4_096, 8, 256, 80, 20_000));

        var attempt = planner.plan(HIDDEN_PYTHAGOREAN);

        assertEquals(Status.BUDGET_INCONCLUSIVE, attempt.status());
        assertTrue(attempt.reachedLimits().contains(LimitReason.DEPTH));
        assertTrue(attempt.application().isEmpty());
    }

    @Test
    void corruptedCertificateFailsIndependentReplay() {
        PatternTargetedLocalBridgePlanner planner = planner(
            Budget.safeDefaults());
        var application = planner.plan(HIDDEN_PYTHAGOREAN)
            .application()
            .orElseThrow();
        var corrupted = application.withCertificate(new Certificate(
            PatternTargetedLocalBridgePlanner.CERTIFICATE_SCHEMA,
            PatternTargetedLocalBridgePlanner.PLANNER_ID,
            "f".repeat(64)));

        assertFalse(planner.verify(corrupted));
    }

    private static PatternTargetedLocalBridgePlanner planner(Budget budget) {
        PatternRewriteRule principal = pythagoreanRule();
        List<RewriteRule> preparationRules =
            AstRewriteTransformationEngine.allBuiltInRules().stream()
                .filter(rule -> "ast_cancel_division_factor".equals(
                    rule.id()))
                .toList();
        return new PatternTargetedLocalBridgePlanner(
            principal,
            new AstRewriteTransformationEngine(preparationRules),
            budget);
    }

    private static PatternRewriteRule pythagoreanRule() {
        PatternExpr x = PatternExpr.var("X");
        PatternExpr sinSquared = PatternExpr.op(
            BinaryOperator.POW,
            PatternExpr.fn("sin", x),
            PatternExpr.num(2));
        PatternExpr cosSquared = PatternExpr.op(
            BinaryOperator.POW,
            PatternExpr.fn("cos", x),
            PatternExpr.num(2));
        return new PatternRewriteRule(
            "sympy.trig.pythagorean",
            PatternExpr.op(
                BinaryOperator.ADD,
                sinSquared,
                cosSquared),
            PatternExpr.num(1),
            RecognitionProfile.arithmeticAc());
    }
}
