package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.knowledge.RuleDescriptor;
import de.regelsuche.rules.RationalRules;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RecognitionProfile;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Negative controls for the V3 direct-stage admission and identity contract. */
class OccurrencePreparationFailureContractTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @Test
    void nullConcreteAssumptionsAreATechnicalFailure() {
        MalformedRule rule = new MalformedRule(false);
        var coordinator = coordinator(rule, false);
        rule.broken = true;

        var evaluation = assertDoesNotThrow(() ->
            coordinator.analyze("1 + (x + 0)", assumptions()));
        var outcome = evaluation.outcome(rule.id()).orElseThrow();

        assertFalse(outcome.positive());
        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            outcome.status());
        assertTrue(evaluation.candidates().isEmpty());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void descriptorFailureDuringReplayIsRetainedInsteadOfEscaping() {
        MalformedRule rule = new MalformedRule(true);
        var coordinator = coordinator(rule, false);
        rule.broken = true;

        var evaluation = assertDoesNotThrow(() ->
            coordinator.analyze("1 + (x + 0)", assumptions()));
        var outcome = evaluation.outcome(rule.id()).orElseThrow();

        assertFalse(outcome.positive());
        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            outcome.status());
        assertTrue(evaluation.candidates().isEmpty());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"b", "β"})
    void occurrenceIdentityUsesUtf8ByteLength(String denominator) {
        var rule = new RationalRules.MultiplyFractionsRule();
        var schema = rule.applicabilitySchema();
        var coordinator = new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(schema), List.of(), REVISION, budget(false));
        var evaluation = coordinator.analyze(
            "1 + (a/" + denominator + ")*(c/d)",
            assumptions(denominator + " != 0", "d != 0"));
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();
        var candidate = evaluation.outcome(rule.id()).orElseThrow()
            .candidate().orElseThrow();

        // Independent byte-oriented encoder; never use the production hash helper.
        String expectedHash = hashUtf8Fields(
            "regelsuche.direct-occurrence-guard-binding/v1",
            schema.contentHash(),
            evaluation.sourceExpression(),
            "$R",
            evidence.sourceSubtree(),
            candidate.transformedExpression());
        assertEquals(expectedHash, evidence.occurrenceHash());
        assertEquals(candidate.applicationKey(), evidence.applicationKey());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void matcherExhaustionIsDistinctFromUnsupportedEvenWithoutGuards(boolean guarded) {
        PatternExpr a = PatternExpr.var("A");
        var rule = new PatternRewriteRule(
            "review_budget_control",
            PatternExpr.fn("ln", PatternExpr.op(
                BinaryOperator.ADD, a, PatternExpr.num(0))),
            PatternExpr.fn("ln", a),
            RecognitionProfile.arithmeticAc());
        var schema = new RewriteApplicabilitySchema(
            "review-budget-control/v1", rule, rule.source(),
            rule.recognitionProfile(),
            guarded ? List.of(RequiredAssumptionTemplate.positive(a)) : List.of());
        var coordinator = new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(schema), List.of(), REVISION, budget(true));

        var evaluation = coordinator.analyze(
            "1 + ln(0 + x)", assumptions("x > 0"));
        var outcome = evaluation.outcome(rule.id()).orElseThrow();

        assertFalse(outcome.positive());
        assertEquals(PatternTargetedLocalBridgeSearch.Status.BUDGET_INCONCLUSIVE,
            outcome.status());
        assertEquals("MATCH_BUDGET_INCONCLUSIVE",
            evaluation.directOccurrence(rule.id()).orElseThrow()
                .analysis().detailCode());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    private static OccurrenceAwareSharedRulePreparationCoordinator coordinator(
        PatternRewriteRule rule, boolean starved
    ) {
        return new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(new RewriteApplicabilitySchema(
                "review-failure-control/v1", rule, rule.source(), rule.recognitionProfile())),
            List.of(), REVISION, budget(starved));
    }

    private static AssumptionSignature assumptions(String... values) {
        return AssumptionSignature.ofExpressions(List.of(values));
    }

    private static PatternTargetedLocalBridgeSearch.Budget budget(boolean starved) {
        return new PatternTargetedLocalBridgeSearch.Budget(
            3, 128, 1_024, 8, 160, 128, 32, 5_000, starved ? 1 : 2_500);
    }

    private static String hashUtf8Fields(String... fields) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
            bytes.writeBytes(Integer.toString(encoded.length)
                .getBytes(StandardCharsets.US_ASCII));
            bytes.write(':');
            bytes.writeBytes(encoded);
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class MalformedRule extends PatternRewriteRule {
        private final boolean failDescriptor;
        private boolean broken;

        private MalformedRule(boolean failDescriptor) {
            super("review_malformed_executor",
                PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)),
                PatternExpr.var("A"));
            this.failDescriptor = failDescriptor;
        }

        @Override
        public List<Assumption> assumptions(Expr subtree) {
            return broken && !failDescriptor ? null : super.assumptions(subtree);
        }

        @Override
        public RuleDescriptor descriptor() {
            if (broken && failDescriptor) {
                throw new IllegalStateException("injected concrete descriptor failure");
            }
            return super.descriptor();
        }
    }
}
