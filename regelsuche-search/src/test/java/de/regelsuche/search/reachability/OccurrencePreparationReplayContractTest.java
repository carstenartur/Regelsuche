package de.regelsuche.search.reachability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.rules.RationalRules;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The occurrence certificate must not replace the executable primitive identity. */
class OccurrencePreparationReplayContractTest {
    private static final String REVISION =
        "0123456789abcdef0123456789abcdef01234567";

    @ParameterizedTest
    @ValueSource(strings = {
        "(a/b)*(c/d)",
        "1 + (a/b)*(c/d)",
        "((a/b)*(c/d)) + 1",
        "sin((a/b)*(c/d))",
        "pair(0, (a/b)*(c/d))",
        "((a/b)*(c/d)) + ((a/b)*(c/d))",
        "1 + (a/β)*(c/d)"
    })
    void directCandidateReplaysThroughTheOrdinaryExecutor(String source) {
        var rule = new RationalRules.MultiplyFractionsRule();
        var assumptions = AssumptionSignature.ofExpressions(
            List.of("b != 0", "β != 0", "d != 0", "z > 0"));
        var coordinator = new OccurrenceAwareSharedRulePreparationCoordinator(
            List.of(rule.applicabilitySchema()), List.of(), REVISION,
            new PatternTargetedLocalBridgeSearch.Budget(
                3, 128, 1_024, 8, 160, 128, 32, 5_000, 2_500));
        var evaluation = coordinator.analyze(source, assumptions);
        var retained = evaluation.outcome(rule.id()).orElseThrow()
            .candidate().orElseThrow();
        // Match the authority's unfiltered first-direct-candidate policy, not
        // the separate product search policy's AST-growth/candidate limits.
        var concrete = new AstRewriteTransformationEngine(
            List.of(rule), Integer.MAX_VALUE, 1)
            .transform(evaluation.sourceExpression()).getFirst();

        assertEquals(concrete.applicationKey(), retained.applicationKey(),
            "A primitive replay must not require an unknown occurrence-v1 key dialect");
        var cumulative = AssumptionSignature.merge(assumptions,
            AssumptionSignature.ofExpressions(concrete.assumptions()));
        var replayed = new Transformation(
            concrete.rule(), concrete.transformedExpression(), concrete.kind(),
            concrete.mayIncreaseComplexity(), concrete.estimatedCostDelta(),
            concrete.equivalencePreservingByConstruction(), concrete.applicationKey(),
            cumulative.normalizedAssumptions(), concrete.packId(), concrete.license(),
            concrete.primitiveRuleIds());
        assertEquals(replayed, retained,
            "Concrete replay must preserve target, metadata, assumptions and provenance");
        var evidence = evaluation.directOccurrence(rule.id()).orElseThrow();
        assertEquals(retained.applicationKey(), evidence.applicationKey());
        assertTrue(evidence.occurrenceHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(coordinator.verify(evaluation).valid());
    }
}
