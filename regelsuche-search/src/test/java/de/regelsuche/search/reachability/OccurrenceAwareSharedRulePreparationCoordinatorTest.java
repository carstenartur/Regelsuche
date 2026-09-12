package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.rules.LogarithmicRules;
import de.regelsuche.rules.RationalRules;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.List;
import org.junit.jupiter.api.Test;

class OccurrenceAwareSharedRulePreparationCoordinatorTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void rootGuardedDirectMatchRemainsAcceptedAndReplayable() {
        RationalRules.MultiplyFractionsRule rule =
            new RationalRules.MultiplyFractionsRule();
        var coordinator = coordinator(rule.applicabilitySchema(), budget());
        var assumptions = assumptions("b != 0", "d != 0");

        var evaluation = coordinator.analyze("(a/b)*(c/d)", assumptions);
        var outcome = evaluation.outcome(rule.id()).orElseThrow();
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();

        assertTrue(outcome.direct());
        assertEquals(1, evaluation.occurrenceWork().directCandidates());
        assertEquals("$", evidence.occurrencePath());
        assertEquals("REQUIRED_ASSUMPTIONS_SATISFIED",
            evidence.guardDetailCode());
        assertEquals(List.of(rule.id()),
            outcome.candidate().orElseThrow().primitiveRuleIds());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void nestedNonZeroGuardUsesTheActualRewriteOccurrence() {
        RationalRules.MultiplyFractionsRule rule =
            new RationalRules.MultiplyFractionsRule();
        var coordinator = coordinator(rule.applicabilitySchema(), budget());
        var assumptions = assumptions("b != 0", "d != 0");

        var evaluation = coordinator.analyze(
            "1 + (a/b)*(c/d)", assumptions);
        var outcome = evaluation.outcome(rule.id()).orElseThrow();
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();

        assertTrue(outcome.direct());
        assertEquals("$R", evidence.occurrencePath());
        assertEquals("a / b * (c / d)", evidence.sourceSubtree());
        assertEquals(List.of("b != 0", "d != 0"),
            evidence.requiredAssumptions());
        assertEquals(outcome.candidate().orElseThrow().applicationKey(),
            evidence.applicationKey());
        assertTrue(evidence.occurrenceHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void nestedGuardFailsClosedWhenOneAssumptionIsMissing() {
        RationalRules.MultiplyFractionsRule rule =
            new RationalRules.MultiplyFractionsRule();
        var coordinator = coordinator(rule.applicabilitySchema(), budget());

        var evaluation = coordinator.analyze(
            "1 + (a/b)*(c/d)", assumptions("b != 0"));
        var outcome = evaluation.outcome(rule.id()).orElseThrow();
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();

        assertFalse(outcome.positive());
        assertEquals(PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED,
            outcome.status());
        assertEquals("REQUIRED_ASSUMPTION_UNKNOWN", outcome.detailCode());
        assertEquals("$R", evidence.occurrencePath());
        assertEquals(0, evaluation.occurrenceWork().directCandidates());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void repeatedEqualOccurrencesSelectTheSameConcreteOccurrenceDeterministically() {
        RationalRules.MultiplyFractionsRule rule =
            new RationalRules.MultiplyFractionsRule();
        var coordinator = coordinator(rule.applicabilitySchema(), budget());
        var assumptions = assumptions("b != 0", "d != 0");
        String source = "((a/b)*(c/d)) + ((a/b)*(c/d))";

        var first = coordinator.analyze(source, assumptions);
        var second = coordinator.analyze(source, assumptions);
        var firstEvidence = first.directOccurrence(rule.id()).orElseThrow();
        var secondEvidence = second.directOccurrence(rule.id()).orElseThrow();

        assertEquals(first, second);
        assertEquals("$L", firstEvidence.occurrencePath());
        assertEquals(firstEvidence, secondEvidence);
        assertEquals(2, first.occurrenceWork().occurrenceCandidates());
        assertTrue(coordinator.verify(first).valid());
    }

    @Test
    void guardFactCannotBeBorrowedFromAnotherCandidateOccurrence() {
        RationalRules.MultiplyFractionsRule rule =
            new RationalRules.MultiplyFractionsRule();
        var coordinator = coordinator(rule.applicabilitySchema(), budget());

        var evaluation = coordinator.analyze(
            "((a/b)*(c/d)) + ((e/f)*(g/h))",
            assumptions("f != 0", "h != 0"));
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();
        var outcome = evaluation.outcome(rule.id()).orElseThrow();

        assertEquals("$L", evidence.occurrencePath());
        assertEquals(List.of("b != 0", "d != 0"), evidence.requiredAssumptions());
        assertFalse(outcome.positive());
        assertEquals("REQUIRED_ASSUMPTION_UNKNOWN", outcome.detailCode());
    }

    @Test
    void nestedPositiveGuardIsBoundToTheLogarithmOccurrence() {
        RewriteRule rule = LogarithmicRules.rules().stream()
            .filter(candidate -> "log_product_split".equals(candidate.id()))
            .findFirst()
            .orElseThrow();
        RewriteApplicabilitySchema schema =
            RewriteApplicabilitySchema.coverageOf(rule).schema();
        var coordinator = coordinator(schema, budget());

        var evaluation = coordinator.analyze(
            "1 + log(a*b)", assumptions("a > 0", "b > 0"));
        var outcome = evaluation.outcome(rule.id()).orElseThrow();
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();

        assertTrue(outcome.direct());
        assertEquals("$R", evidence.occurrencePath());
        assertEquals(List.of("a > 0", "b > 0"),
            evidence.requiredAssumptions());
        assertEquals("SATISFIED", evidence.guardStatus());
    }

    @Test
    void matcherBudgetExhaustionCannotAuthorizeAGuardedOccurrence() {
        PatternExpr a = PatternExpr.var("A");
        PatternExpr sumWithZero = PatternExpr.op(
            BinaryOperator.ADD, a, PatternExpr.num(0));
        PatternRewriteRule rule = new PatternRewriteRule(
            "occurrence_guard_budget",
            PatternExpr.fn("ln", sumWithZero),
            PatternExpr.fn("ln", a),
            RecognitionProfile.arithmeticAc());
        RewriteApplicabilitySchema schema = new RewriteApplicabilitySchema(
            "occurrence-guard-budget/v1",
            rule,
            rule.source(),
            rule.recognitionProfile(),
            List.of(RequiredAssumptionTemplate.positive(a)));
        var coordinator = coordinator(schema, patternBranchStarvedBudget());

        var evaluation = coordinator.analyze(
            "1 + ln(0 + x)", assumptions("x > 0"));
        var outcome = evaluation.outcome(rule.id()).orElseThrow();
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();

        assertEquals("$R", evidence.occurrencePath());
        assertEquals("MATCH_BUDGET_INCONCLUSIVE",
            evidence.analysis().detailCode());
        assertFalse(outcome.positive());
        assertEquals("REQUIRED_ASSUMPTION_BINDINGS_UNAVAILABLE",
            outcome.detailCode());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void malformedGuardTemplateFailsClosedInsteadOfUsingUnrelatedBindings() {
        PatternExpr a = PatternExpr.var("A");
        PatternRewriteRule rule = new PatternRewriteRule(
            "occurrence_malformed_guard",
            PatternExpr.fn("ln", a),
            a,
            RewriteKind.SIMPLIFY,
            false,
            -1,
            true);
        RewriteApplicabilitySchema schema = new RewriteApplicabilitySchema(
            "occurrence-malformed-guard/v1",
            rule,
            rule.source(),
            rule.recognitionProfile(),
            List.of(RequiredAssumptionTemplate.positive(
                PatternExpr.var("MISSING"))));
        var coordinator = coordinator(schema, budget());

        var evaluation = coordinator.analyze(
            "1 + ln(x)", assumptions("x > 0"));
        var outcome = evaluation.outcome(rule.id()).orElseThrow();

        assertFalse(outcome.positive());
        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            outcome.status());
        assertEquals("REQUIRED_ASSUMPTION_TEMPLATE_INVALID",
            outcome.detailCode());
        assertEquals(0, evaluation.occurrenceWork().directCandidates());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void v2HistoricalAuthorityIsNotReinterpretedByV3() {
        RationalRules.MultiplyFractionsRule rule =
            new RationalRules.MultiplyFractionsRule();
        RewriteApplicabilitySchema schema = rule.applicabilitySchema();
        var assumptions = assumptions("b != 0", "d != 0");
        String source = "1 + (a/b)*(c/d)";
        var v2 = new SharedUnifiedRulePreparationCoordinator(
            List.of(schema), List.of(), REVISION, budget());
        var v3 = coordinator(schema, budget());

        var v2Evaluation = v2.analyze(source, assumptions);
        var v3Evaluation = v3.analyze(source, assumptions);

        assertEquals(
            "regelsuche.unified-safe-rule-preparation-coordinator/v2",
            v2Evaluation.coordinatorId());
        assertEquals(OccurrenceAwareSharedRulePreparationCoordinator.COORDINATOR_ID,
            v3Evaluation.coordinatorId());
        assertNotEquals(v2Evaluation.coordinatorId(), v3Evaluation.coordinatorId());
        assertFalse(v2Evaluation.outcome(rule.id()).orElseThrow().positive());
        assertTrue(v3Evaluation.outcome(rule.id()).orElseThrow().direct());
        assertTrue(v2.verify(v2Evaluation).valid());
        assertTrue(v3.verify(v3Evaluation).valid());
    }

    @Test
    void nonDirectPrincipalsDelegateToV2WithoutLosingPrimitiveLineage() {
        PatternRewriteRule preparation = patternRule(
            "v3_prepare_difference_of_squares",
            expandedDifferenceOfSquares(),
            factoredDifferenceOfSquares(),
            RewriteKind.FACTOR);
        PatternRewriteRule principal = patternRule(
            "v3_expand_factored_difference",
            factoredDifferenceOfSquares(),
            expandedDifferenceOfSquares(),
            RewriteKind.EXPAND);
        var coordinator = new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(RewriteApplicabilitySchema.fromPatternRule(principal)),
            List.of(preparation),
            REVISION,
            budget());

        var evaluation = coordinator.analyze(
            "x^2-y^2", assumptions());
        var outcome = evaluation.outcome(principal.id()).orElseThrow();

        assertTrue(outcome.prepared());
        assertEquals(List.of(preparation.id(), principal.id()),
            outcome.candidate().orElseThrow().primitiveRuleIds());
        assertEquals(List.of(principal.id()),
            evaluation.delegatedV2PrincipalIds());
        assertTrue(evaluation.delegatedSharedExecutionWork().isPresent());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    private static OccurrenceAwareSharedRulePreparationCoordinator coordinator(
        RewriteApplicabilitySchema schema,
        PatternTargetedLocalBridgeSearch.Budget budget
    ) {
        return new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(schema), List.of(), REVISION, budget);
    }

    private static AssumptionSignature assumptions(String... values) {
        return AssumptionSignature.ofExpressions(List.of(values));
    }

    private static PatternRewriteRule patternRule(
        String id,
        PatternExpr source,
        PatternExpr target,
        RewriteKind kind
    ) {
        return new PatternRewriteRule(
            id, source, target, kind, false, -1, true);
    }

    private static PatternExpr expandedDifferenceOfSquares() {
        return PatternExpr.op(
            BinaryOperator.SUB,
            square("A"),
            square("B"));
    }

    private static PatternExpr factoredDifferenceOfSquares() {
        return PatternExpr.op(
            BinaryOperator.MUL,
            PatternExpr.op(
                BinaryOperator.SUB,
                PatternExpr.var("A"),
                PatternExpr.var("B")),
            PatternExpr.op(
                BinaryOperator.ADD,
                PatternExpr.var("A"),
                PatternExpr.var("B")));
    }

    private static PatternExpr square(String placeholder) {
        return PatternExpr.op(
            BinaryOperator.POW,
            PatternExpr.var(placeholder),
            PatternExpr.num(2));
    }

    private static PatternTargetedLocalBridgeSearch.Budget budget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500);
    }

    private static PatternTargetedLocalBridgeSearch.Budget patternBranchStarvedBudget() {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128, 32, 5_000, 1);
    }
}
