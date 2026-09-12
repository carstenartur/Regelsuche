package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RequiredAssumptionTemplate;
import de.regelsuche.transform.RewriteApplicabilitySchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OccurrencePreparationGuardContractTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";
    private static final AssumptionSignature NO_ASSUMPTIONS =
        AssumptionSignature.ofExpressions(List.of());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedTemplateDominatesUnknownGuardsInEitherOrder(boolean invalidFirst) {
        var a = PatternExpr.var("A");
        var unknown = RequiredAssumptionTemplate.positive(a);
        var invalid = RequiredAssumptionTemplate.positive(PatternExpr.var("MISSING"));
        var later = RequiredAssumptionTemplate.nonZero(a);
        var requirements = invalidFirst
            ? List.of(invalid, unknown, later)
            : List.of(unknown, invalid, later);
        var schema = schema("invalid_guard", requirements);
        var coordinator = coordinator(List.of(schema));

        var evaluation = coordinator.analyze("1 + ln(x)", NO_ASSUMPTIONS);
        var outcome = evaluation.outcome(schema.ruleId()).orElseThrow();
        var evidence = evaluation.directOccurrence(schema.ruleId()).orElseThrow();

        assertEquals(PatternTargetedLocalBridgeSearch.Status.TECHNICAL_FAILURE,
            outcome.status());
        assertEquals("REQUIRED_ASSUMPTION_TEMPLATE_INVALID", outcome.detailCode());
        assertEquals("INVALID", evidence.guardStatus());
        assertEquals(List.of("x > 0", "x != 0"), evidence.requiredAssumptions());
        assertTrue(evaluation.candidates().isEmpty());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    @Test
    void unknownGuardRetainsLaterRequirementsWhenSharingFactsAcrossPrincipals() {
        var a = PatternExpr.var("A");
        var requirements = List.of(
            RequiredAssumptionTemplate.positive(a),
            RequiredAssumptionTemplate.nonZero(a));
        var first = schema("first_guard", requirements);
        var second = schema("second_guard", requirements);
        var coordinator = coordinator(List.of(first, second));

        var evaluation = coordinator.analyze("1 + ln(x)", NO_ASSUMPTIONS);

        for (var schema : List.of(first, second)) {
            var outcome = evaluation.outcome(schema.ruleId()).orElseThrow();
            var evidence = evaluation.directOccurrence(schema.ruleId()).orElseThrow();
            assertEquals(PatternTargetedLocalBridgeSearch.Status.UNSUPPORTED, outcome.status());
            assertEquals("UNKNOWN", evidence.guardStatus());
            assertEquals(List.of("x > 0", "x != 0"), evidence.requiredAssumptions());
        }
        assertEquals(2, evaluation.occurrenceWork().guardRequests());
        assertEquals(1, evaluation.occurrenceWork().uniqueGuardFacts());
        assertEquals(1, evaluation.occurrenceWork().guardCacheHits());
        assertTrue(evaluation.candidates().isEmpty());
        assertTrue(coordinator.verify(evaluation).valid());
    }

    private static RewriteApplicabilitySchema schema(
        String ruleId,
        List<RequiredAssumptionTemplate> requirements
    ) {
        var a = PatternExpr.var("A");
        var rule = new PatternRewriteRule(ruleId, PatternExpr.fn("ln", a), a);
        return RewriteApplicabilitySchema.fromPatternRule(rule, requirements);
    }

    private static OccurrenceAwareSharedRulePreparationCoordinator coordinator(
        List<RewriteApplicabilitySchema> schemas
    ) {
        return new OccurrenceAwareSharedRulePreparationCoordinator(
            schemas, List.of(), REVISION,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500));
    }
}
