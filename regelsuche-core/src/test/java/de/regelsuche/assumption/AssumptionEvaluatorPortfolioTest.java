package de.regelsuche.assumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssumptionEvaluatorPortfolioTest {
    @Test
    void localEvaluatorUsesTypedImplication() {
        AssumptionContext context = new AssumptionContext();
        context.add(Assumption.natural("n"));

        AssumptionEvaluation evaluation =
            AssumptionEvaluatorPortfolio.localOnly().evaluate(
                Assumption.rational("n"), context);

        assertEquals(AssumptionTruthValue.TRUE, evaluation.result());
        assertTrue(evaluation.isSatisfied());
        assertFalse(evaluation.isRejected());
        assertFalse(evaluation.isInconclusive());
        assertFalse(evaluation.conflicting());
        assertEquals(
            List.of("NATURAL|n ∈ N"),
            evaluation.contextSignature().normalizedAssumptions());
        assertEquals(
            "core.known-assumptions",
            evaluation.evidence().get(0).evaluatorId());
        assertEquals(
            AssumptionEvaluationDisposition.EVALUATED,
            evaluation.evidence().get(0).disposition());
        assertTrue(evaluation.evaluatorProfileHash()
            .matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void emptyContextRemainsUnknown() {
        AssumptionEvaluation evaluation =
            AssumptionEvaluatorPortfolio.localOnly().evaluate(
                Assumption.positive("x"), new AssumptionContext());

        assertEquals(AssumptionTruthValue.UNKNOWN, evaluation.result());
        assertTrue(evaluation.isInconclusive());
        assertFalse(evaluation.conflicting());
    }

    @Test
    void conflictingEvaluatorsFailClosedAndRemainVisible() {
        AssumptionEvaluatorPortfolio portfolio =
            new AssumptionEvaluatorPortfolio(List.of(
                new FixedEvaluator(
                    "z-evaluator",
                    "v1",
                    AssumptionTruthValue.TRUE,
                    AssumptionEvaluationDisposition.EVALUATED
                ),
                new FixedEvaluator(
                    "a-evaluator",
                    "v1",
                    AssumptionTruthValue.FALSE,
                    AssumptionEvaluationDisposition.EVALUATED
                )
            ));

        AssumptionEvaluation evaluation = portfolio.evaluate(
            Assumption.positive("x"), List.of());

        assertEquals(AssumptionTruthValue.UNKNOWN, evaluation.result());
        assertTrue(evaluation.conflicting());
        assertTrue(evaluation.isInconclusive());
        assertEquals(
            List.of("a-evaluator", "z-evaluator"),
            evaluation.evidence().stream()
                .map(AssumptionEvaluationEvidence::evaluatorId)
                .toList());
    }

    @Test
    void unsupportedEvaluatorIsTypedAndInconclusive() {
        AssumptionEvaluatorPortfolio portfolio =
            new AssumptionEvaluatorPortfolio(List.of(
                new FixedEvaluator(
                    "external",
                    "v1",
                    AssumptionTruthValue.UNKNOWN,
                    AssumptionEvaluationDisposition.UNSUPPORTED
                )
            ));

        AssumptionEvaluation evaluation = portfolio.evaluate(
            Assumption.positive("x"), List.of());

        assertEquals(AssumptionTruthValue.UNKNOWN, evaluation.result());
        assertEquals(
            AssumptionEvaluationDisposition.UNSUPPORTED,
            evaluation.evidence().get(0).disposition());
        assertFalse(evaluation.conflicting());
    }

    @Test
    void portfolioRejectsDuplicateEvaluatorIds() {
        assertThrows(IllegalArgumentException.class, () ->
            new AssumptionEvaluatorPortfolio(List.of(
                new FixedEvaluator(
                    "duplicate",
                    "v1",
                    AssumptionTruthValue.UNKNOWN,
                    AssumptionEvaluationDisposition.EVALUATED
                ),
                new FixedEvaluator(
                    "duplicate",
                    "v2",
                    AssumptionTruthValue.UNKNOWN,
                    AssumptionEvaluationDisposition.EVALUATED
                )
            )));
    }

    @Test
    void portfolioHashIsOrderIndependentAndRevisionSensitive() {
        FixedEvaluator first = new FixedEvaluator(
            "first",
            "v1",
            AssumptionTruthValue.UNKNOWN,
            AssumptionEvaluationDisposition.EVALUATED
        );
        FixedEvaluator second = new FixedEvaluator(
            "second",
            "v1",
            AssumptionTruthValue.UNKNOWN,
            AssumptionEvaluationDisposition.EVALUATED
        );

        String forward = new AssumptionEvaluatorPortfolio(
            List.of(first, second)).contentHash();
        String reverse = new AssumptionEvaluatorPortfolio(
            List.of(second, first)).contentHash();
        String changedRevision = new AssumptionEvaluatorPortfolio(List.of(
            first,
            new FixedEvaluator(
                "second",
                "v2",
                AssumptionTruthValue.UNKNOWN,
                AssumptionEvaluationDisposition.EVALUATED
            )
        )).contentHash();

        assertEquals(forward, reverse);
        assertNotEquals(forward, changedRevision);
    }

    @Test
    void portfolioRejectsMisattributedEvidence() {
        AssumptionEvaluatorPortfolio portfolio =
            new AssumptionEvaluatorPortfolio(List.of(
                new MisattributingEvaluator()));

        assertThrows(IllegalArgumentException.class, () ->
            portfolio.evaluate(Assumption.positive("x"), List.of()));
    }

    @Test
    void nonEvaluatedEvidenceCannotCarryDecisiveResult() {
        assertThrows(IllegalArgumentException.class, () ->
            new AssumptionEvaluationEvidence(
                "external",
                "v1",
                AssumptionTruthValue.TRUE,
                AssumptionEvaluationDisposition.TIMEOUT,
                "timeout",
                ""
            ));
    }

    private record FixedEvaluator(
        String id,
        String revision,
        AssumptionTruthValue result,
        AssumptionEvaluationDisposition disposition
    ) implements AssumptionEvaluator {
        @Override
        public AssumptionEvaluationEvidence evaluate(
            Assumption requiredAssumption,
            List<Assumption> knownAssumptions
        ) {
            if (disposition == AssumptionEvaluationDisposition.EVALUATED) {
                return AssumptionEvaluationEvidence.evaluated(
                    id, revision, result, "fixed", "");
            }
            return AssumptionEvaluationEvidence.inconclusive(
                id, revision, disposition, "fixed", "");
        }
    }

    private static final class MisattributingEvaluator
            implements AssumptionEvaluator {
        @Override
        public String id() {
            return "declared";
        }

        @Override
        public String revision() {
            return "v1";
        }

        @Override
        public AssumptionEvaluationEvidence evaluate(
            Assumption requiredAssumption,
            List<Assumption> knownAssumptions
        ) {
            return AssumptionEvaluationEvidence.evaluated(
                "other",
                revision(),
                AssumptionTruthValue.UNKNOWN,
                "misattributed",
                ""
            );
        }
    }
}
