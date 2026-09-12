package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import org.junit.jupiter.api.Test;

class OccurrencePreparationWorkContractTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void noMatchTraversalWorkGrowsWithVisitedNodes() {
        var coordinator = coordinator(List.of());
        var small = coordinator.analyze("x", AssumptionSignature.empty());
        var large = coordinator.analyze("f(a,b,c,d,e,f,g,h)", AssumptionSignature.empty());

        assertEquals(0, small.occurrenceWork().occurrenceCandidates());
        assertEquals(0, large.occurrenceWork().occurrenceCandidates());
        assertTrue(large.occurrenceWork().chargedUnits()
                > small.occurrenceWork().chargedUnits(),
            "a no-match scan of nine AST nodes must cost more than one node");
    }

    @Test
    void fallbackConstructionHasAnExplicitNonzeroCharge() {
        var coordinator = coordinator(List.of());
        var evaluation = coordinator.analyze("x", AssumptionSignature.empty());

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
        var empty = coordinator(List.of()).analyze("x", AssumptionSignature.empty());
        var larger = coordinator(List.of(preparation)).analyze("x", AssumptionSignature.empty());

        assertTrue(larger.occurrenceWork().chargedUnits()
                > empty.occurrenceWork().chargedUnits(),
            "validating and indexing a larger delegate inventory is not free");
    }

    @Test
    void repeatedAnalysisAndVerificationRetainDeterministicWork() {
        var coordinator = coordinator(List.of());
        var first = coordinator.analyze("f(a,b,c)", AssumptionSignature.empty());
        var second = coordinator.analyze("f(a,b,c)", AssumptionSignature.empty());
        assertEquals(first, second);
        assertTrue(coordinator.verify(first).valid());
        assertEquals(first, coordinator.analyze("f(a,b,c)", AssumptionSignature.empty()));
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
