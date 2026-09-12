package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class OccurrencePreparationWorkContractTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final AssumptionSignature NO_ASSUMPTIONS =
        AssumptionSignature.ofExpressions(List.of());

    @Test
    void noMatchTraversalWorkGrowsWithVisitedNodes() {
        var coordinator = coordinator(List.of());
        var small = coordinator.analyze("x", NO_ASSUMPTIONS);
        var large = coordinator.analyze("f(a,b,c,d,e,f,g,h)", NO_ASSUMPTIONS);

        assertEquals(0, small.occurrenceWork().occurrenceCandidates());
        assertEquals(0, large.occurrenceWork().occurrenceCandidates());
        assertTrue(large.occurrenceWork().chargedUnits()
                > small.occurrenceWork().chargedUnits(),
            "a no-match scan of nine AST nodes must cost more than one node");
    }

    @Test
    void fallbackConstructionHasAnExplicitNonzeroCharge() {
        var coordinator = coordinator(List.of());
        var evaluation = coordinator.analyze("x", NO_ASSUMPTIONS);

        assertEquals(List.of("work_log"), evaluation.delegatedV2PrincipalIds());
        assertTrue(evaluation.occurrenceWork().chargedUnits() > 2,
            "one rule dispatch and one node attempt cannot cover repeated delegate setup");
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void delegateSetupChargeGrowsWithThePreparationInventory() {
        PatternExpr a = PatternExpr.var("A");
        var preparation = new PatternRewriteRule(
            "work_add_zero", PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)),
            a, RewriteKind.SIMPLIFY, false, -1, true);
        var empty = coordinator(List.of()).analyze("x", NO_ASSUMPTIONS);
        var larger = coordinator(List.of(preparation)).analyze("x", NO_ASSUMPTIONS);

        assertTrue(larger.occurrenceWork().chargedUnits()
                > empty.occurrenceWork().chargedUnits(),
            "validating and indexing a larger delegate inventory is not free");
    }

    @Test
    void repeatedAnalysisAndVerificationRetainDeterministicWork() {
        var coordinator = coordinator(List.of());
        var first = coordinator.analyze("f(a,b,c)", NO_ASSUMPTIONS);
        var second = coordinator.analyze("f(a,b,c)", NO_ASSUMPTIONS);
        assertEquals(first, second);
        assertTrue(coordinator.verify(first).valid());
        assertEquals(first, coordinator.analyze("f(a,b,c)", NO_ASSUMPTIONS));
    }

    @Test
    void directCallsIncludeEveryVisitedNodeButDoNotChargeUnusedDelegate() {
        var coordinator = coordinator(List.of());
        var evaluation = coordinator.analyze("1 + ln(x)", NO_ASSUMPTIONS);
        var work = evaluation.occurrenceWork();
        assertEquals(4, work.directMatchAttempts());
        assertEquals(1, work.directApplyAttempts());
        assertEquals(1, work.directAssumptionRequests());
        assertEquals(0, work.delegateSetupUnits());
        assertEquals(10, work.chargedUnits());
        assertEquals(OccurrenceAwareSharedRulePreparationCoordinator.OCCURRENCE_WORK_REVISION,
            work.revision());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void delegateInputSlotsHaveAnIndependentExpectedCharge() {
        var evaluation = coordinator(List.of()).analyze("x", NO_ASSUMPTIONS);
        // R=1, P=0: construction + inventory + per-principal visible input = 1+1+1.
        assertEquals(3, evaluation.occurrenceWork().delegateSetupUnits());
        assertEquals(5, evaluation.occurrenceWork().chargedUnits());
        var a = PatternExpr.var("A");
        var preparation = new PatternRewriteRule("work_zero",
            PatternExpr.op(BinaryOperator.ADD, a, PatternExpr.num(0)), a);
        var larger = coordinator(List.of(preparation)).analyze("x", NO_ASSUMPTIONS);
        assertEquals(5, larger.occurrenceWork().delegateSetupUnits());
        assertEquals(7, larger.occurrenceWork().chargedUnits());
    }

    @Test
    void noOpRewritesStillChargeTheirApplyAttempts() {
        var a = PatternExpr.var("A");
        var identity = new PatternRewriteRule("work_identity", a, a);
        var coordinator = forPrincipal(identity);
        var evaluation = coordinator.analyze("f(x,y)", NO_ASSUMPTIONS);
        var work = evaluation.occurrenceWork();
        assertEquals(3, work.directMatchAttempts());
        assertEquals(3, work.directApplyAttempts());
        assertEquals(0, work.directAssumptionRequests());
        assertEquals(0, work.occurrenceCandidates());
        assertEquals(10, work.chargedUnits());
    }

    @Test
    void failureDoesNotEraseSuccessfulPrefixOrFailingMatchWork() {
        var a = PatternExpr.var("A");
        var failing = new PatternRewriteRule("work_failing", PatternExpr.fn("ln", a), a) {
            @Override
            public boolean matches(Expr subtree) {
                if (subtree instanceof VariableExpr variable && variable.name().equals("stop")) {
                    throw new IllegalStateException("fixture match failure");
                }
                return super.matches(subtree);
            }
        };
        var coordinator = forPrincipal(failing);
        var evaluation = coordinator.analyze("f(ln(x),stop)", NO_ASSUMPTIONS);
        var work = evaluation.occurrenceWork();
        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            evaluation.outcome(failing.id()).orElseThrow().status());
        assertEquals(4, work.directMatchAttempts());
        assertEquals(1, work.directApplyAttempts());
        assertEquals(1, work.directAssumptionRequests());
        assertEquals(0, work.delegateSetupUnits());
        assertEquals(7, work.chargedUnits());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void invalidCallRelationshipsAndOverflowFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
            new OccurrenceAwareSharedRulePreparationCoordinator.OccurrenceWork(
                1, 0, 0, 0, 0, 0, 0, 1, 2, 0, 0));
        assertThrows(IllegalArgumentException.class, () ->
            new OccurrenceAwareSharedRulePreparationCoordinator.OccurrenceWork(
                1, 0, 0, 0, 0, 0, 0, 1, 1, 2, 0));
        assertThrows(ArithmeticException.class, () ->
            new OccurrenceAwareSharedRulePreparationCoordinator.OccurrenceWork(
                Long.MAX_VALUE, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0).chargedUnits());
    }

    private static OccurrenceAwareSharedRulePreparationCoordinator forPrincipal(
        PatternRewriteRule rule
    ) {
        return new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(RewriteApplicabilitySchema.fromPatternRule(rule)), List.of(),
            REVISION, new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500));
    }

    private static OccurrenceAwareSharedRulePreparationCoordinator coordinator(
        List<PatternRewriteRule> preparation
    ) {
        PatternExpr a = PatternExpr.var("A");
        var principal = new PatternRewriteRule(
            "work_log", PatternExpr.fn("ln", a), a,
            RewriteKind.SIMPLIFY, false, -1, true);
        return new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(RewriteApplicabilitySchema.fromPatternRule(principal)), preparation,
            REVISION, new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500));
    }
}
