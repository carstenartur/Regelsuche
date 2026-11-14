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
            defaultBudget());
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
    void metersFormationAndConcreteCertificateReplaySeparately() {
        var search = search(pythagoreanRule(), cancellationRules(), defaultBudget());
        var ledger = new PatternTargetedLocalBridgeSearch.OperationLedger();
        var assumptions = AssumptionSignature.ofExpressions(List.of());
        var attempt = search.analyze(PYTHAGOREAN_SOURCE, assumptions, ledger);
        var bridge = attempt.bridge().orElseThrow();
        assertEquals(search.analyze(PYTHAGOREAN_SOURCE, assumptions), attempt);
        var formation = ledger.formation();
        assertEquals(attempt.work().analyzedCandidates() + 1L, formation.patternAnalyses());
        assertTrue(formation.evaluatedMatchSteps() > 0);
        assertTrue(formation.structuralComparisons() > 0);
        assertEquals(1, formation.principalReplayCalls());
        assertEquals(attempt.work().expandedStates(), formation.preparationEngineCalls());

        var verification = ledger.certificateVerification();
        assertEquals(1, ledger.certificateChecks());
        assertEquals(1, ledger.certificateHashesChecked());
        assertThrows(IllegalStateException.class, () -> search.analyze(PYTHAGOREAN_SOURCE, assumptions, ledger));
        assertEquals(bridge.preparationSteps().size(), verification.preparationEngineCalls());
        assertTrue(verification.returnedPreparationTransitions() > verification.preparationEngineCalls());
        assertEquals(1, verification.patternAnalyses());
        assertEquals(1, verification.principalReplayCalls());
        var replay = new PatternTargetedLocalBridgeSearch.OperationLedger();
        assertTrue(search.verify(bridge, replay).valid());
        assertEquals(verification, replay.certificateVerification());
        assertEquals(0, replay.formation().patternAnalyses());
    }

    @Test
    void metersDepthLimitProbeAndMissingCertificateWithoutCreditingReplay() {
        var budget = new PatternTargetedLocalBridgeSearch.Budget(0, 16, 16, 4, 128, 16, 8, 128, 64);
        var search = search(pythagoreanRule(), cancellationRules(), budget);
        var ledger = new PatternTargetedLocalBridgeSearch.OperationLedger();
        var attempt = search.analyze(PYTHAGOREAN_SOURCE, AssumptionSignature.ofExpressions(List.of()), ledger);
        assertEquals(PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE, attempt.status());
        assertEquals(0, attempt.work().expandedStates());
        assertEquals(1, ledger.formation().preparationEngineCalls());
        assertTrue(ledger.formation().returnedPreparationTransitions() > 0);
        assertEquals(0, ledger.certificateChecks());
        assertFalse(search.verify(null, ledger).valid());
        assertEquals(1, ledger.certificateChecks());
        assertEquals(0, ledger.certificateHashesChecked());
        assertEquals(0, ledger.certificateVerification().preparationEngineCalls());
        assertEquals(0, ledger.certificateVerification().patternAnalyses());
        assertEquals(0, ledger.certificateVerification().principalReplayCalls());
    }

    @Test
    void peakIncludesAnOversizedGeneratedCandidateThatNeverEntersTheFrontier() {
        var grow = new PatternRewriteRule("grow-a", PatternExpr.variable("a"),
            PatternExpr.op(BinaryOperator.ADD, PatternExpr.variable("a"), PatternExpr.num(1)));
        var budget = new PatternTargetedLocalBridgeSearch.Budget(2, 8, 8, 2, 1, 8, 8, 128, 64);
        var search = search(pattern("principal-z", "z", "y"), List.of(grow), budget);
        var ledger = new PatternTargetedLocalBridgeSearch.OperationLedger();
        var attempt = search.analyze("a", AssumptionSignature.ofExpressions(List.of()), ledger);
        assertTrue(attempt.reachedLimits().contains("EXPRESSION_NODES"));
        assertEquals(0, attempt.work().retainedTransitions());
        assertEquals(1, ledger.sourceAstNodes());
        assertEquals(1, ledger.sizedCandidateAsts());
        assertEquals(3, ledger.peakObservedAstNodes());
    }

    @Test
    void directMatchDoesNotManufacturePreparationEvidence() {
        PatternTargetedLocalBridgeSearch search = search(
            pythagoreanRule(),
            cancellationRules(),
            defaultBudget());

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
            defaultBudget());

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
            defaultBudget());
        PatternTargetedLocalBridgeSearch.Bridge bridge = search.analyze(
            PYTHAGOREAN_SOURCE,
            AssumptionSignature.ofExpressions(List.of()))
            .bridge().orElseThrow();

        PatternTargetedLocalBridgeSearch.Bridge corrupted =
            bridge.withCertificateHash("sha256:" + "0".repeat(64));
        assertFalse(search.verify(corrupted).valid());
        assertThrows(
            IllegalArgumentException.class,
            () -> search(principal, List.of(principal), defaultBudget()));
        assertNotNull(bridge.principalStep().applicationKey());
    }

    private static PatternTargetedLocalBridgeSearch.Budget defaultBudget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 128, 128,
            32, 5_000, 2_500);
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
