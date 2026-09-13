package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.rules.RationalRules;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.RewriteRule;
import de.regelsuche.transform.Transformation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContextualOccurrenceRulePreparationCoordinatorTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void nestedPreparationProducesAContextBoundReplayableSuccessor()
        throws Exception {
        Class<?> authorityType = Class.forName(
            "de.regelsuche.search.reachability."
                + "ContextualOccurrenceRulePreparationCoordinator");
        Class<?> budgetType = Class.forName(
            authorityType.getName() + "$ContextualBudget");
        Object contextualBudget = budgetType
            .getConstructor(long.class, int.class)
            .newInstance(100_000L, 32);
        Constructor<?> constructor = authorityType.getConstructor(
            List.class,
            List.class,
            String.class,
            PatternTargetedLocalBridgeSearch.Budget.class,
            budgetType);
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        RewriteRule addZero = AstRewriteTransformationEngine
            .allBuiltInRules().stream()
            .filter(rule -> "ast_add_zero_right".equals(rule.id()))
            .findFirst()
            .orElseThrow();
        Object authority = constructor.newInstance(
            List.of(principal.applicabilitySchema()),
            List.of(addZero),
            REVISION,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500),
            contextualBudget);
        AssumptionSignature assumptions = AssumptionSignature.ofExpressions(
            List.of("b != 0", "d != 0"));

        Method analyze = authorityType.getMethod(
            "analyze", String.class, AssumptionSignature.class);
        Object evaluation = analyze.invoke(
            authority, "1+((a/b)+0)*(c/d)", assumptions);
        Object outcome = evaluation.getClass()
            .getMethod("outcome", String.class)
            .invoke(evaluation, principal.id());
        Object retained = ((java.util.Optional<?>) outcome).orElseThrow();
        assertTrue((boolean) retained.getClass()
            .getMethod("prepared").invoke(retained), () -> {
                try {
                    return "unexpected outcome: " + retained.getClass()
                        .getMethod("detailCode").invoke(retained);
                } catch (ReflectiveOperationException exception) {
                    return exception.toString();
                }
            });
        Transformation candidate = (Transformation) ((java.util.Optional<?>)
            retained.getClass().getMethod("candidate").invoke(retained))
            .orElseThrow();
        assertEquals("1 + a * c / (b * d)",
            candidate.transformedExpression());
        assertEquals(List.of("ast_add_zero_right", principal.id()),
            candidate.primitiveRuleIds());

        Object verification = authorityType.getMethod(
            "verify", evaluation.getClass()).invoke(authority, evaluation);
        assertTrue((boolean) verification.getClass()
            .getMethod("valid").invoke(verification));
    }

    @Test
    void v4RetainsV3AsAnUnchangedNegativeBaseReceipt() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var authority = authority(principal, 100_000, 32);
        var assumptions = assumptions("b != 0", "d != 0");
        String source = "1+((a/b)+0)*(c/d)";

        var evaluation = authority.analyze(source, assumptions);
        var independentV3 = new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(principal.applicabilitySchema()), List.of(addZero()),
            REVISION, bridgeBudget()).analyze(source, assumptions);

        assertEquals(independentV3, evaluation.baseV3Evaluation());
        assertFalse(evaluation.baseV3Evaluation().outcome(principal.id())
            .orElseThrow().positive());
        assertEquals("$R", evaluation.successors().getFirst().occurrencePath());
        assertEquals(2, evaluation.successors().getFirst().localSteps().size());
        assertTrue(evaluation.successors().getFirst().liftedExecutionJson()
            .contains("ast_add_zero_right"));
    }

    @Test
    void contextualGuardCannotAuthorizeWithAMissingLocalDenominatorFact() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var authority = authority(principal, 100_000, 32);

        var evaluation = authority.analyze(
            "1+((a/b)+0)*(c/d)", assumptions("b != 0"));
        var outcome = evaluation.outcome(principal.id()).orElseThrow();

        assertFalse(outcome.positive());
        assertEquals(PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED,
            outcome.status());
        assertEquals("REQUIRED_ASSUMPTION_UNKNOWN", outcome.detailCode());
        assertTrue(evaluation.successors().isEmpty());
        assertTrue(authority.verify(evaluation).valid());
    }

    @Test
    void liftedExecutionRetainsOpaqueInitialAssumptions() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var authority = authority(principal, 100_000, 32);

        var evaluation = authority.analyze(
            "1+((a/b)+0)*(c/d)",
            assumptions("u != 0", "b != 0", "d != 0"));
        Transformation candidate = evaluation.outcome(principal.id())
            .orElseThrow().candidate().orElseThrow();

        assertEquals(List.of("b != 0", "d != 0", "u != 0"),
            candidate.assumptions());
        assertTrue(evaluation.successors().getFirst().liftedExecutionJson()
            .contains("u != 0"));
        assertTrue(authority.verify(evaluation).valid());
    }

    @Test
    void globalBudgetStopsBeforeOccurrenceBatchesAndRetainsBaseWork() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var authority = authority(principal, 0, 32);

        var evaluation = authority.analyze(
            "1+((a/b)+0)*(c/d)", assumptions("b != 0", "d != 0"));
        var outcome = evaluation.outcome(principal.id()).orElseThrow();

        assertEquals(PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE,
            outcome.status());
        assertEquals("V4_GLOBAL_ANALYSIS_BUDGET_EXHAUSTED",
            outcome.detailCode());
        assertEquals(0, evaluation.work().occurrenceBatches());
        assertEquals(evaluation.work().baseAnalysisUnits(),
            evaluation.work().analysisUnits());
        assertTrue(evaluation.work().limitReached());
        assertTrue(authority.verify(evaluation).valid());
    }

    @Test
    void ordinaryV3DirectOutcomeIsNotReinterpretedAsAContextualSuccessor() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var authority = authority(principal, 100_000, 32);

        var evaluation = authority.analyze(
            "1+(a/b)*(c/d)", assumptions("b != 0", "d != 0"));

        assertTrue(evaluation.outcome(principal.id()).orElseThrow().direct());
        assertEquals(evaluation.baseV3Evaluation().outcomes(),
            evaluation.outcomes());
        assertTrue(evaluation.successors().isEmpty());
        assertTrue(evaluation.work().verificationReservationUnits()
            > evaluation.work().analysisUnits());
        assertTrue(authority.verify(evaluation).valid());
    }

    @Test
    void verificationRejectsChangedDomainAndWorkReceipts() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var authority = authority(principal, 100_000, 32);
        var original = authority.analyze(
            "1+((a/b)+0)*(c/d)", assumptions("b != 0", "d != 0"));
        var changedDomain = copy(original, assumptions("b != 0"), original.work());
        var work = original.work();
        var changedWork = new ContextualOccurrenceRulePreparationCoordinator.ContextualWork(
            work.revision(), work.baseAnalysisUnits(), work.occurrenceNodes(),
            work.occurrenceBatches(), work.localBatchUnits(),
            work.failedBatchReservationUnits(), work.localAuthorizationUnits(),
            work.liftedReplayUnits(), work.failedLiftReservationUnits(),
            work.analysisUnits() + 1, work.baseDirectReplayReservationUnits(),
            work.contextualReplayReservationUnits(),
            work.verificationReservationUnits() + 1, work.limitReached());

        assertFalse(authority.verify(changedDomain).valid());
        assertFalse(authority.verify(copy(
            original, original.sourceAssumptions(), changedWork)).valid());
    }

    @Test
    void verificationRejectsAChangedOccurrencePath() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var authority = authority(principal, 100_000, 32);
        var original = authority.analyze(
            "1+((a/b)+0)*(c/d)", assumptions("b != 0", "d != 0"));
        var evidence = original.successors().getFirst();
        var changed = new ContextualOccurrenceRulePreparationCoordinator
            .ContextualSuccessorEvidence(
                evidence.revision(), evidence.successorIdentity(),
                evidence.ruleId(), evidence.ruleFingerprint(), "$L",
                evidence.sourceSubtree(), evidence.terminalSubtree(),
                evidence.resultSubtree(), evidence.resultExpression(),
                evidence.localSteps(), evidence.initialAnalysis(),
                evidence.terminalAnalysis(), evidence.localWork(),
                evidence.localCertificateHash(), evidence.liftedExecutionJson(),
                evidence.liftedExecutionHash(), evidence.replayUnits());
        var tampered = new ContextualOccurrenceRulePreparationCoordinator.Evaluation(
            original.coordinatorId(), original.successorIdentityRevision(),
            original.repositoryRevision(), original.principalInventoryFingerprint(),
            original.preparationInventoryFingerprint(),
            original.exactRegistryFingerprint(), original.bridgeBudget(),
            original.contextualBudget(), original.sourceExpression(),
            original.sourceAssumptions(), original.baseV3Evaluation(),
            original.outcomes(), List.of(changed), original.work());

        assertFalse(authority.verify(tampered).valid());
    }

    @Test
    void localPrincipalFailureIsRetainedAsATechnicalOutcome() {
        RationalRules.MultiplyFractionsRule delegate =
            new RationalRules.MultiplyFractionsRule();
        RewriteRule failing = new ThrowingApplyRule(delegate);
        var sourceSchema = delegate.applicabilitySchema();
        var schema = new de.regelsuche.transform.RewriteApplicabilitySchema(
            "v4-throwing-rational/v1", failing, sourceSchema.pattern(),
            sourceSchema.recognitionProfile(),
            sourceSchema.requiredAssumptions());
        var authority = new ContextualOccurrenceRulePreparationCoordinator(
            List.of(schema), List.of(addZero()), REVISION, bridgeBudget(),
            new ContextualOccurrenceRulePreparationCoordinator.ContextualBudget(
                100_000, 32));

        var evaluation = authority.analyze(
            "1+((a/b)+0)*(c/d)", assumptions("b != 0", "d != 0"));
        var outcome = evaluation.outcome(failing.id()).orElseThrow();

        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            outcome.status());
        assertEquals("UNIFIED_SHARED_PRINCIPAL_REPLAY_TECHNICAL_FAILURE",
            outcome.detailCode());
        assertTrue(evaluation.successors().isEmpty());
        assertTrue(authority.verify(evaluation).valid());
    }

    @Test
    void failedTraversalRetainsAConservativeLogicalReservation()
        throws Exception {
        var authority = workAuthority(new FailingAddZeroRule(2));

        var evaluation = authority.analyze(
            "1+((x+0)*x)", assumptions());

        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            evaluation.outcome("review-square").orElseThrow().status());
        assertTrue(workCounter(
            evaluation.work(), "failedBatchReservationUnits") > 0);
        assertTrue(evaluation.work().localBatchUnits()
            > evaluation.work().occurrenceBatches());
    }

    @Test
    void failedLiftRetainsItsConcreteReplayReservation()
        throws Exception {
        var authority = workAuthority(new FailingAddZeroRule(3));

        var evaluation = authority.analyze(
            "1+((x+0)*x)", assumptions());

        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            evaluation.outcome("review-square").orElseThrow().status());
        assertTrue(workCounter(
            evaluation.work(), "failedLiftReservationUnits") > 0);
        assertTrue(evaluation.work().liftedReplayUnits() > 0);
    }

    @Test
    void laterUnsupportedOccurrenceCannotEraseEarlierBudgetInconclusive() {
        RationalRules.MultiplyFractionsRule principal =
            new RationalRules.MultiplyFractionsRule();
        var shallowBridge = new PatternTargetedLocalBridgeSearch.Budget(
            1, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500);
        var authority = new ContextualOccurrenceRulePreparationCoordinator(
            List.of(principal.applicabilitySchema()), List.of(addZero()),
            REVISION, shallowBridge,
            new ContextualOccurrenceRulePreparationCoordinator.ContextualBudget(
                100_000, 64));

        var evaluation = authority.analyze(
            "(((a/b)+0)+0)*(c/d)+((e/f)+0)*(g/h)",
            assumptions("b != 0", "d != 0"));
        var outcome = evaluation.outcome(principal.id()).orElseThrow();

        assertEquals(PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE,
            outcome.status());
        assertFalse(outcome.positive());
        assertTrue(authority.verify(evaluation).valid());
    }

    @Test
    void failedTraversalReservationCoversAllBoundedPrincipalAnalyses()
        throws Exception {
        List<de.regelsuche.transform.RewriteApplicabilitySchema> schemas =
            java.util.stream.IntStream.range(0, 20)
                .mapToObj(NoMatchRule::new)
                .map(rule -> rule.explicitApplicabilitySchema().orElseThrow())
                .toList();
        var bounded = new PatternTargetedLocalBridgeSearch.Budget(
            8, 8, 8, 8, 160, 128, 32, 5_000, 2_500);
        GrowingPreparationRule preparation = new GrowingPreparationRule();
        var authority = new ContextualOccurrenceRulePreparationCoordinator(
            schemas, List.of(preparation), REVISION, bounded,
            new ContextualOccurrenceRulePreparationCoordinator.ContextualBudget(
                100_000, 64));
        Method reservation = authority.getClass().getDeclaredMethod(
            "failedTraversalReservation", Expr.class, int.class);
        reservation.setAccessible(true);

        long units = (long) reservation.invoke(
            authority, new VariableExpr("x"), 20);
        var traversal = new SharedMultiPrincipalPreparationTraversal(
            schemas, List.of(preparation), bounded);
        assertThrows(IllegalStateException.class,
            () -> traversal.analyze("x", assumptions()));
        var sharedField = traversal.getClass().getDeclaredField("shared");
        sharedField.setAccessible(true);
        Object shared = sharedField.get(traversal);
        var requestsField = shared.getClass()
            .getDeclaredField("analysisRequests");
        requestsField.setAccessible(true);
        long completedAnalysisRequests = requestsField.getLong(shared);

        assertEquals(320, completedAnalysisRequests,
            "fixture must reach the reviewed late-failure boundary");
        assertTrue(units >= completedAnalysisRequests,
            "reservation did not cover work already completed before throw");
    }

    private static ContextualOccurrenceRulePreparationCoordinator.Evaluation copy(
        ContextualOccurrenceRulePreparationCoordinator.Evaluation source,
        AssumptionSignature assumptions,
        ContextualOccurrenceRulePreparationCoordinator.ContextualWork work
    ) {
        return new ContextualOccurrenceRulePreparationCoordinator.Evaluation(
            source.coordinatorId(), source.successorIdentityRevision(),
            source.repositoryRevision(), source.principalInventoryFingerprint(),
            source.preparationInventoryFingerprint(),
            source.exactRegistryFingerprint(), source.bridgeBudget(),
            source.contextualBudget(), source.sourceExpression(), assumptions,
            source.baseV3Evaluation(), source.outcomes(), source.successors(), work);
    }

    private static ContextualOccurrenceRulePreparationCoordinator authority(
        RationalRules.MultiplyFractionsRule principal,
        long maxAnalysisUnits,
        int maxOccurrenceBatches
    ) {
        return new ContextualOccurrenceRulePreparationCoordinator(
            List.of(principal.applicabilitySchema()), List.of(addZero()),
            REVISION, bridgeBudget(),
            new ContextualOccurrenceRulePreparationCoordinator.ContextualBudget(
                maxAnalysisUnits, maxOccurrenceBatches));
    }

    private static RewriteRule addZero() {
        return AstRewriteTransformationEngine.allBuiltInRules().stream()
            .filter(rule -> "ast_add_zero_right".equals(rule.id()))
            .findFirst().orElseThrow();
    }

    private static PatternTargetedLocalBridgeSearch.Budget bridgeBudget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500);
    }

    private static AssumptionSignature assumptions(String... values) {
        return AssumptionSignature.ofExpressions(List.of(values));
    }

    private static long workCounter(
        ContextualOccurrenceRulePreparationCoordinator.ContextualWork work,
        String accessor
    ) throws Exception {
        return (long) work.getClass().getMethod(accessor).invoke(work);
    }

    private static ContextualOccurrenceRulePreparationCoordinator workAuthority(
        RewriteRule preparation
    ) {
        SquareRule principal = new SquareRule();
        return new ContextualOccurrenceRulePreparationCoordinator(
            List.of(principal.explicitApplicabilitySchema().orElseThrow()),
            List.of(preparation), REVISION, bridgeBudget(),
            new ContextualOccurrenceRulePreparationCoordinator.ContextualBudget(
                100_000, 64));
    }

    private record ThrowingApplyRule(RewriteRule delegate)
        implements RewriteRule {
        @Override public String id() { return delegate.id(); }
        @Override public de.regelsuche.transform.RewriteKind kind() {
            return delegate.kind();
        }
        @Override public boolean mayIncreaseComplexity() {
            return delegate.mayIncreaseComplexity();
        }
        @Override public int estimatedCostDelta() {
            return delegate.estimatedCostDelta();
        }
        @Override public boolean isEquivalencePreservingByConstruction() {
            return delegate.isEquivalencePreservingByConstruction();
        }
        @Override public boolean matches(Expr subtree) {
            return delegate.matches(subtree);
        }
        @Override public Expr apply(Expr subtree) {
            throw new IllegalStateException("injected principal failure");
        }
        @Override public java.util.List<de.regelsuche.assumption.Assumption>
                assumptions(Expr subtree) {
            return delegate.assumptions(subtree);
        }
        @Override public boolean mayEmitAssumptions() {
            return delegate.mayEmitAssumptions();
        }
    }

    private static final class SquareRule implements RewriteRule {
        @Override public String id() { return "review-square"; }
        @Override public de.regelsuche.transform.RewriteKind kind() {
            return de.regelsuche.transform.RewriteKind.NORMALIZE;
        }
        @Override public boolean mayIncreaseComplexity() { return false; }
        @Override public int estimatedCostDelta() { return 0; }
        @Override public boolean isEquivalencePreservingByConstruction() {
            return true;
        }
        @Override public boolean matches(Expr expression) {
            return expression instanceof BinaryExpr binary
                && binary.operator() == BinaryOperator.MUL
                && binary.left().equals(binary.right());
        }
        @Override public Expr apply(Expr expression) {
            BinaryExpr binary = (BinaryExpr) expression;
            return new BinaryExpr(binary.left(), BinaryOperator.POW,
                NumberExpr.exact("2"));
        }
        @Override public java.util.Optional<de.regelsuche.transform
                .RewriteApplicabilitySchema> explicitApplicabilitySchema() {
            return java.util.Optional.of(new de.regelsuche.transform
                .RewriteApplicabilitySchema(
                    "review-square/v1", this,
                    de.regelsuche.transform.PatternExpr.op(
                        BinaryOperator.MUL,
                        de.regelsuche.transform.PatternExpr.var("x"),
                        de.regelsuche.transform.PatternExpr.var("x")),
                    de.regelsuche.transform.RecognitionProfile.exact()));
        }
    }

    private static final class FailingAddZeroRule implements RewriteRule {
        private final RewriteRule delegate = addZero();
        private final int failAt;
        private int applications;

        private FailingAddZeroRule(int failAt) {
            this.failAt = failAt;
        }

        @Override public String id() { return "review-zero"; }
        @Override public de.regelsuche.transform.RewriteKind kind() {
            return delegate.kind();
        }
        @Override public boolean mayIncreaseComplexity() {
            return delegate.mayIncreaseComplexity();
        }
        @Override public int estimatedCostDelta() {
            return delegate.estimatedCostDelta();
        }
        @Override public boolean isEquivalencePreservingByConstruction() {
            return true;
        }
        @Override public boolean matches(Expr expression) {
            return delegate.matches(expression);
        }
        @Override public Expr apply(Expr expression) {
            applications++;
            if (applications == failAt) {
                throw new IllegalStateException("injected preparation failure");
            }
            return delegate.apply(expression);
        }
    }

    private record NoMatchRule(int number) implements RewriteRule {
        @Override public String id() { return "never-" + number; }
        @Override public de.regelsuche.transform.RewriteKind kind() {
            return de.regelsuche.transform.RewriteKind.NORMALIZE;
        }
        @Override public boolean mayIncreaseComplexity() { return false; }
        @Override public int estimatedCostDelta() { return 0; }
        @Override public boolean isEquivalencePreservingByConstruction() {
            return true;
        }
        @Override public boolean matches(Expr expression) { return false; }
        @Override public Expr apply(Expr expression) { return expression; }
        @Override public java.util.Optional<de.regelsuche.transform
                .RewriteApplicabilitySchema> explicitApplicabilitySchema() {
            return java.util.Optional.of(new de.regelsuche.transform
                .RewriteApplicabilitySchema(
                    "never/v1:" + number, this,
                    de.regelsuche.transform.PatternExpr.variable(
                        "missing" + number),
                    de.regelsuche.transform.RecognitionProfile.exact()));
        }
    }

    private static final class GrowingPreparationRule implements RewriteRule {
        private int applications;

        @Override public String id() { return "grow-zero"; }
        @Override public de.regelsuche.transform.RewriteKind kind() {
            return de.regelsuche.transform.RewriteKind.NORMALIZE;
        }
        @Override public boolean mayIncreaseComplexity() { return true; }
        @Override public int estimatedCostDelta() { return 2; }
        @Override public boolean isEquivalencePreservingByConstruction() {
            return true;
        }
        @Override public boolean matches(Expr expression) {
            return expression instanceof VariableExpr variable
                && "x".equals(variable.name());
        }
        @Override public Expr apply(Expr expression) {
            applications++;
            if (applications == 8) {
                throw new IllegalStateException("late bounded failure");
            }
            return new BinaryExpr(
                expression, BinaryOperator.ADD, NumberExpr.exact("0"));
        }
    }
}
