package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleInventoryFingerprint;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternMatchAnalyzer;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternTargetedLocalBridgeSearchTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final String PYTHAGOREAN_SOURCE =
        "((sin(x) * a) / a)^2 + ((cos(x) * b) / b)^2";

    @Test
    void amplifiesPythagoreanRuleThroughTwoCertifiedCancellations() {
        PatternRewriteRule principal = pythagoreanRule();
        List<RewriteRule> preparationRules = cancellationRules();
        assertTrue(new AstRewriteTransformationEngine(List.of(principal))
            .transform(PYTHAGOREAN_SOURCE).isEmpty());

        PatternTargetedLocalBridgeSearch search = search(
            principal,
            preparationRules,
            PatternTargetedLocalBridgeSearch.Budget.defaults());
        PatternTargetedLocalBridgeSearch.Attempt attempt = search.analyze(
            PYTHAGOREAN_SOURCE,
            AssumptionSignature.ofExpressions(List.of()));

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status.PREPARED,
            attempt.status());
        assertEquals(
            PatternMatchAnalyzer.Status.RESIDUAL,
            attempt.initialAnalysis().status());
        PatternTargetedLocalBridgeSearch.Bridge bridge =
            attempt.bridge().orElseThrow();
        assertEquals("1", bridge.resultExpression());
        assertEquals(2, bridge.preparationSteps().size());
        assertEquals(
            List.of(
                "ast_cancel_division_factor",
                "ast_cancel_division_factor",
                "sympy.trig.pythagorean"),
            bridge.primitiveRuleIds());
        assertEquals(
            List.of("a != 0", "b != 0"),
            bridge.resultAssumptions().normalizedAssumptions());
        assertTrue(bridge.terminalAnalysis().status()
            == PatternMatchAnalyzer.Status.EXACT_MATCH
            || bridge.terminalAnalysis().status()
                == PatternMatchAnalyzer.Status.MATCH_MODULO_THEORY);
        assertTrue(bridge.certificateHash()
            .matches("sha256:[0-9a-f]{64}"));
        assertEquals(
            RuleInventoryFingerprint.contentHash(preparationRules),
            bridge.preparationInventoryFingerprint());
        assertEquals(
            RuleInventoryFingerprint.ruleContentHash(principal),
            bridge.principalRuleFingerprint());
        assertTrue(search.verify(bridge).valid());
    }

    @Test
    void directMatchDoesNotManufacturePreparationEvidence() {
        PatternTargetedLocalBridgeSearch search = search(
            pythagoreanRule(),
            cancellationRules(),
            PatternTargetedLocalBridgeSearch.Budget.defaults());

        PatternTargetedLocalBridgeSearch.Attempt attempt = search.analyze(
            "sin(x)^2 + cos(x)^2",
            AssumptionSignature.ofExpressions(List.of()));

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status.DIRECT_MATCH_AVAILABLE,
            attempt.status());
        assertTrue(attempt.bridge().isEmpty());
        assertEquals(0, attempt.work().generatedTransitions());
    }

    @Test
    void finiteEmptyPreparationClosureIsAConclusiveNoBridge() {
        PatternTargetedLocalBridgeSearch search = search(
            pythagoreanRule(),
            List.of(),
            PatternTargetedLocalBridgeSearch.Budget.defaults());

        PatternTargetedLocalBridgeSearch.Attempt attempt = search.analyze(
            "sin(x)^2 + cos(y)^2",
            AssumptionSignature.ofExpressions(List.of()));

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status
                .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            attempt.status());
        assertTrue(attempt.reachedLimits().isEmpty());
        assertTrue(attempt.bridge().isEmpty());
    }

    @Test
    void unseenSecondCancellationAtDepthLimitIsInconclusive() {
        PatternTargetedLocalBridgeSearch.Budget budget =
            new PatternTargetedLocalBridgeSearch.Budget(
                1, 128, 1_024, 8, 128, 128,
                32, 5_000, 2_500);
        PatternTargetedLocalBridgeSearch search = search(
            pythagoreanRule(), cancellationRules(), budget);

        PatternTargetedLocalBridgeSearch.Attempt attempt = search.analyze(
            PYTHAGOREAN_SOURCE,
            AssumptionSignature.ofExpressions(List.of()));

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE,
            attempt.status());
        assertTrue(attempt.reachedLimits().contains("DEPTH"));
        assertTrue(attempt.bridge().isEmpty());
    }

    @Test
    void exactStateIdentityRetainsEqualSyntaxUnderDifferentAssumptions() {
        PatternRewriteRule principal = new PatternRewriteRule(
            "principal-z",
            PatternExpr.variable("z"),
            PatternExpr.num(1));
        RewriteRule first = new AssumptionPatternRule(
            "a-to-b-under-p",
            PatternExpr.variable("a"),
            PatternExpr.variable("b"),
            "p");
        RewriteRule second = new AssumptionPatternRule(
            "a-to-b-under-q",
            PatternExpr.variable("a"),
            PatternExpr.variable("b"),
            "q");
        PatternTargetedLocalBridgeSearch search = search(
            principal,
            List.of(first, second),
            new PatternTargetedLocalBridgeSearch.Budget(
                2, 16, 16, 4, 16, 16,
                8, 128, 64));

        PatternTargetedLocalBridgeSearch.Attempt attempt = search.analyze(
            "a",
            AssumptionSignature.ofExpressions(List.of()));

        assertEquals(
            PatternTargetedLocalBridgeSearch.Status
                .NO_BRIDGE_IN_COMPLETE_FROZEN_CLOSURE,
            attempt.status());
        assertEquals(3, attempt.work().discoveredStates());
        assertEquals(0, attempt.work().duplicateTransitions());
    }

    @Test
    void repeatedExecutionChoosesTheSameShortestBridge() {
        PatternRewriteRule principal = new PatternRewriteRule(
            "principal-d",
            PatternExpr.variable("d"),
            PatternExpr.num(1));
        List<RewriteRule> rules = List.of(
            pattern("a-to-b", "a", "b"),
            pattern("a-to-c", "a", "c"),
            pattern("b-to-d", "b", "d"),
            pattern("c-to-d", "c", "d"));
        PatternTargetedLocalBridgeSearch search = search(
            principal,
            rules,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 32, 64, 8, 32, 32,
                8, 128, 64));

        PatternTargetedLocalBridgeSearch.Bridge first = search.analyze(
            "a",
            AssumptionSignature.ofExpressions(List.of()))
            .bridge().orElseThrow();
        PatternTargetedLocalBridgeSearch.Bridge second = search.analyze(
            "a",
            AssumptionSignature.ofExpressions(List.of()))
            .bridge().orElseThrow();

        assertEquals(first, second);
        assertEquals(2, first.preparationSteps().size());
        assertTrue(search.verify(first).valid());
    }

    @Test
    void corruptedCertificateAndInvalidInventoriesFailClosed() {
        PatternRewriteRule principal = pythagoreanRule();
        List<RewriteRule> rules = cancellationRules();
        PatternTargetedLocalBridgeSearch search = search(
            principal,
            rules,
            PatternTargetedLocalBridgeSearch.Budget.defaults());
        PatternTargetedLocalBridgeSearch.Bridge bridge = search.analyze(
            PYTHAGOREAN_SOURCE,
            AssumptionSignature.ofExpressions(List.of()))
            .bridge().orElseThrow();

        PatternTargetedLocalBridgeSearch.Bridge corrupted =
            bridge.withCertificateHash("sha256:" + "0".repeat(64));
        assertFalse(search.verify(corrupted).valid());
        assertThrows(
            IllegalArgumentException.class,
            () -> search(principal, List.of(principal),
                PatternTargetedLocalBridgeSearch.Budget.defaults()));
        assertNotNull(bridge.principalStep().applicationKey());
    }

    private static PatternTargetedLocalBridgeSearch search(
        PatternRewriteRule principal,
        List<? extends RewriteRule> preparationRules,
        PatternTargetedLocalBridgeSearch.Budget budget
    ) {
        return new PatternTargetedLocalBridgeSearch(
            principal, preparationRules, REVISION, budget);
    }

    private static PatternRewriteRule pythagoreanRule() {
        PatternExpr x = PatternExpr.var("X");
        return new PatternRewriteRule(
            "sympy.trig.pythagorean",
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.op(
                    BinaryOperator.POW,
                    PatternExpr.fn("sin", x),
                    PatternExpr.num(2)),
                PatternExpr.op(
                    BinaryOperator.POW,
                    PatternExpr.fn("cos", x),
                    PatternExpr.num(2))),
            PatternExpr.num(1),
            RecognitionProfile.arithmeticAc());
    }

    private static List<RewriteRule> cancellationRules() {
        List<RewriteRule> result = AstRewriteTransformationEngine
            .allBuiltInRules().stream()
            .filter(rule -> "ast_cancel_division_factor"
                .equals(rule.id()))
            .toList();
        assertEquals(1, result.size());
        return result;
    }

    private static PatternRewriteRule pattern(
        String id,
        String source,
        String target
    ) {
        return new PatternRewriteRule(
            id,
            PatternExpr.variable(source),
            PatternExpr.variable(target));
    }

    private static final class AssumptionPatternRule
            extends PatternRewriteRule {
        private final String nonZeroSymbol;

        private AssumptionPatternRule(
            String id,
            PatternExpr source,
            PatternExpr target,
            String nonZeroSymbol
        ) {
            super(id, source, target);
            this.nonZeroSymbol = nonZeroSymbol;
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            return List.of(Assumption.nonZero(nonZeroSymbol));
        }

        @Override
        public boolean mayEmitAssumptions() {
            return true;
        }
    }
}
