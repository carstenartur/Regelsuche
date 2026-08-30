package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheorySubsumptionClassifierTest {
    private static final String TEST_HASH =
        "sha256:" + "0".repeat(64);

    @Test
    void positiveClassificationCanOnlyBeIssuedByTheClassifier() {
        assertEquals(
            0,
            PolynomialTheorySubsumptionClassifier.Classification.class
                .getConstructors()
                .length,
            "cache callers must not manufacture theory-subsumption evidence");
    }

    @Test
    void requiresOneExplicitFactorizationEngine() {
        assertThrows(
            NullPointerException.class,
            () -> new PolynomialTheorySubsumptionClassifier(null));
    }

    @Test
    void blankInputIsRejectedBeforeTheEngineRuns() {
        StubEngine engine = StubEngine.noCandidate();
        PolynomialTheorySubsumptionClassifier classifier =
            new PolynomialTheorySubsumptionClassifier(engine);

        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(" ", "x");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.UNSUPPORTED,
            result.status());
        assertEquals(0, engine.calls);
        assertNoCacheCandidate(result);
    }

    @Test
    void noCandidateIsNotMisreportedAsIrreducibilityOrSubsumption() {
        StubEngine engine = StubEngine.noCandidate();
        PolynomialTheorySubsumptionClassifier classifier =
            new PolynomialTheorySubsumptionClassifier(engine);

        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify("x^2 - 1", "(x - 1) * (x + 1)");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.NOT_SUBSUMED,
            result.status());
        assertEquals(1, engine.calls);
        assertFalse(result.subsumed());
        assertTrue(result.workUnits() > 0);
        assertNoCacheCandidate(result);
    }

    @Test
    void unsupportedAndBudgetOutcomesRemainSeparate() {
        PolynomialTheorySubsumptionClassifier.Classification unsupported =
            new PolynomialTheorySubsumptionClassifier(
                StubEngine.outcome(
                    FactorizationEngine.Outcome.UNSUPPORTED_REQUEST))
                .classify("x^2 - 1", "(x - 1) * (x + 1)");
        PolynomialTheorySubsumptionClassifier.Classification budget =
            new PolynomialTheorySubsumptionClassifier(
                StubEngine.outcome(
                    FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE))
                .classify("x^2 - 1", "(x - 1) * (x + 1)");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.UNSUPPORTED,
            unsupported.status());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.BUDGET_INCONCLUSIVE,
            budget.status());
        assertNoCacheCandidate(unsupported);
        assertNoCacheCandidate(budget);
    }

    @Test
    void engineFailureRemainsTechnicalAndFailClosed() {
        PolynomialTheorySubsumptionClassifier classifier =
            new PolynomialTheorySubsumptionClassifier(
                StubEngine.throwing());

        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify("x^2 - 1", "(x - 1) * (x + 1)");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.TECHNICAL_FAILURE,
            result.status());
        assertNoCacheCandidate(result);
    }

    @Test
    void multivariateSourceIsRejectedByTheExactUnivariateView() {
        StubEngine engine = StubEngine.noCandidate();
        PolynomialTheorySubsumptionClassifier classifier =
            new PolynomialTheorySubsumptionClassifier(engine);

        PolynomialTheorySubsumptionClassifier.Classification result =
            classifier.classify(
                "x^2 - y^2",
                "(x - y) * (x + y)");

        assertEquals(
            PolynomialTheorySubsumptionClassifier.Status.UNSUPPORTED,
            result.status());
        assertEquals(0, engine.calls);
        assertNoCacheCandidate(result);
    }

    private void assertNoCacheCandidate(
        PolynomialTheorySubsumptionClassifier.Classification result
    ) {
        assertTrue(result.sourceExpression().isEmpty());
        assertTrue(result.certificateHash().isEmpty());
        assertTrue(result.derivedExpression().isEmpty());
        assertTrue(result.applicationKey().isEmpty());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.RetentionDisposition.NONE,
            result.retentionDisposition());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.ProjectInventoryNovelty
                .NOT_EVALUATED,
            result.projectInventoryNovelty());
        assertEquals(
            PolynomialTheorySubsumptionClassifier.THEORY_METHOD_ID,
            result.theoryMethodId());
    }

    private static final class StubEngine
            implements FactorizationEngine<ExactRational> {
        private final Outcome outcome;
        private final boolean throwFailure;
        private int calls;

        private StubEngine(Outcome outcome, boolean throwFailure) {
            this.outcome = outcome;
            this.throwFailure = throwFailure;
        }

        private static StubEngine noCandidate() {
            return outcome(Outcome.NO_CANDIDATE);
        }

        private static StubEngine outcome(Outcome outcome) {
            return new StubEngine(outcome, false);
        }

        private static StubEngine throwing() {
            return new StubEngine(Outcome.TECHNICAL_FAILURE, true);
        }

        @Override
        public String engineId() {
            return "regelsuche.test-polynomial-theory-engine/v1";
        }

        @Override
        public String coefficientDomainId() {
            return ExactRationalField.DOMAIN_ID;
        }

        @Override
        public EngineResult<ExactRational> propose(
            FactorizationRequest<ExactRational> request
        ) {
            calls++;
            if (throwFailure) {
                throw new IllegalStateException("test engine failure");
            }
            return new EngineResult<>(
                engineId(),
                outcome,
                "TEST_" + outcome.name(),
                PolynomialWorkLedger.empty(),
                List.of(),
                BackendClaim.NONE,
                TEST_HASH);
        }
    }
}
