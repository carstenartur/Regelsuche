package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Public analyze/verify boundaries, not just the independent replay helper. */
class OccurrencePreparationVerificationBoundaryTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final AssumptionSignature ASSUMPTIONS =
        AssumptionSignature.ofExpressions(List.of());
    private static final String DELEGATE_FAILURE =
        "UNIFIED_V3_DELEGATE_TECHNICAL_FAILURE";

    @Test
    void malformedRetainedSourceReturnsTechnicalVerificationInsteadOfThrowing() {
        var rule = new FailingRule("principal", "f");
        var coordinator = coordinator(List.of(rule), List.of());
        var valid = coordinator.analyze("f(x)", ASSUMPTIONS);
        var malformed = copy(valid, "(", valid.outcomes(), valid.delegatedSharedExecutionWork());

        var verification = assertDoesNotThrow(() -> coordinator.verify(malformed));

        assertFalse(verification.valid());
        assertEquals("EVALUATION_RECOMPUTATION_TECHNICAL_FAILURE", verification.detailCode());
    }

    @Test
    void executorFailureAfterPositiveEvaluationIsNotReportedAsOrdinaryMismatch() {
        var rule = new FailingRule("principal", "f");
        var coordinator = coordinator(List.of(rule), List.of());
        var evaluation = coordinator.analyze("f(x)", ASSUMPTIONS);
        assertTrue(evaluation.outcome(rule.id()).orElseThrow().direct());
        assertTrue(coordinator.verify(evaluation).valid());
        rule.failMatches = true;

        var verification = assertDoesNotThrow(() -> coordinator.verify(evaluation));

        assertFalse(verification.valid());
        assertEquals("UNIFIED_V3_DIRECT_REPLAY_TECHNICAL_FAILURE", verification.detailCode());
    }

    @Test
    void delegateSetupFailureRetainsEveryUnresolvedPrincipalAndTheSetupCharge() {
        var first = new FailingRule("first", "f");
        var second = new FailingRule("second", "g");
        var preparation = new FailingRule("preparation", "h");
        var coordinator = coordinator(List.of(first, second), List.of(preparation));
        preparation.failDescriptor = true;

        var evaluation = assertDoesNotThrow(() -> coordinator.analyze("x", ASSUMPTIONS));

        assertEquals(List.of(first.id(), second.id()), evaluation.delegatedV2PrincipalIds());
        assertEquals(8, evaluation.occurrenceWork().delegateSetupUnits());
        assertTrue(evaluation.delegatedSharedExecutionWork().isEmpty());
        assertTrue(evaluation.candidates().isEmpty());
        for (var outcome : evaluation.outcomes()) {
            assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE, outcome.status());
            assertEquals(DELEGATE_FAILURE, outcome.detailCode());
            assertFalse(outcome.positive());
        }
        // A reproducible failure receipt may verify; that does not make it a success.
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void delegateFailureDoesNotDiscardAnIndependentDirectCandidate() {
        var direct = new FailingRule("direct", "f");
        var unresolved = new FailingRule("unresolved", "g");
        var preparation = new FailingRule("preparation", "h");
        var coordinator = coordinator(List.of(direct, unresolved), List.of(preparation));
        preparation.failDescriptor = true;

        var evaluation = assertDoesNotThrow(() -> coordinator.analyze("f(x)", ASSUMPTIONS));

        assertTrue(evaluation.outcome(direct.id()).orElseThrow().direct());
        assertEquals(DELEGATE_FAILURE, evaluation.outcome(unresolved.id()).orElseThrow().detailCode());
        assertEquals(1, evaluation.candidates().size());
        assertEquals(5, evaluation.occurrenceWork().delegateSetupUnits());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void delegateFailureDuringVerificationHasItsOwnTechnicalReason() {
        var principal = new FailingRule("principal", "f");
        var preparation = new FailingRule("preparation", "h");
        var coordinator = coordinator(List.of(principal), List.of(preparation));
        var evaluation = coordinator.analyze("x", ASSUMPTIONS);
        assertTrue(evaluation.delegatedSharedExecutionWork().isPresent());
        preparation.failDescriptor = true;

        var verification = assertDoesNotThrow(() -> coordinator.verify(evaluation));

        assertFalse(verification.valid());
        assertEquals(DELEGATE_FAILURE, verification.detailCode());
    }

    @Test
    void completedDelegationCannotLoseItsWorkReceipt() {
        var principal = new FailingRule("principal", "f");
        var coordinator = coordinator(List.of(principal), List.of());
        var evaluation = coordinator.analyze("x", ASSUMPTIONS);
        assertTrue(evaluation.delegatedSharedExecutionWork().isPresent());

        assertThrows(IllegalArgumentException.class, () ->
            copy(evaluation, evaluation.sourceExpression(), evaluation.outcomes(), Optional.empty()));
    }

    private static OccurrenceAwareSharedRulePreparationCoordinator.Evaluation copy(
        OccurrenceAwareSharedRulePreparationCoordinator.Evaluation original,
        String source,
        List<RulePreparationCoordinator.Outcome> outcomes,
        Optional<SharedUnifiedRulePreparationCoordinator.SharedExecutionWork> delegatedWork
    ) {
        return new OccurrenceAwareSharedRulePreparationCoordinator.Evaluation(
            original.coordinatorId(), original.occurrenceBindingRevision(),
            original.repositoryRevision(), original.principalInventoryFingerprint(),
            original.preparationInventoryFingerprint(), original.exactRegistryFingerprint(),
            original.bridgeBudget(), source, original.sourceAssumptions(), outcomes,
            original.aggregateWork(), original.occurrenceWork(), original.directOccurrenceEvidence(),
            original.delegatedV2PrincipalIds(), delegatedWork);
    }

    private static OccurrenceAwareSharedRulePreparationCoordinator coordinator(
        List<FailingRule> principals, List<FailingRule> preparationRules
    ) {
        return new OccurrenceAwareSharedRulePreparationCoordinator(
            principals.stream().map(rule -> new RewriteApplicabilitySchema(
                "verification-boundary/v1:" + rule.id(), rule, rule.source(),
                rule.recognitionProfile())).toList(),
            preparationRules, REVISION,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500));
    }

    private static final class FailingRule extends PatternRewriteRule {
        private boolean failMatches;
        private boolean failDescriptor;

        private FailingRule(String id, String function) {
            super(id, PatternExpr.fn(function, PatternExpr.var("A")), PatternExpr.var("A"));
        }

        @Override
        public boolean matches(Expr subtree) {
            if (failMatches) throw new IllegalStateException("injected match failure");
            return super.matches(subtree);
        }

        @Override
        public RuleDescriptor descriptor() {
            if (failDescriptor) throw new IllegalStateException("injected descriptor failure");
            return super.descriptor();
        }
    }
}
