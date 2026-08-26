package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SymPyFactorizationEngineTest {
    private static final FactorizationRequest.StructuralLimits LIMITS =
        new FactorizationRequest.StructuralLimits(
            4,
            16,
            128,
            4_096);

    @Test
    void embeddedIntegerEngineReusesItsContextAndKeepsEvidenceStable() {
        FactorizationRequest<BigInteger> request = integerQuarticRequest();

        try (GraalPySymPyFactorizationEngine<BigInteger> engine =
                GraalPySymPyFactorizationEngine.integers()) {
            FactorizationVerifier.Report<BigInteger> first =
                FactorizationVerifier.execute(engine, request);
            SymPyExecutionMetrics cold = engine.lastExecutionMetrics()
                .orElseThrow();
            FactorizationVerifier.Report<BigInteger> second =
                FactorizationVerifier.execute(engine, request);
            SymPyExecutionMetrics warm = engine.lastExecutionMetrics()
                .orElseThrow();

            assertSuccessfulCompleteBackendProposal(first);
            assertEquals(first, second,
                "noncanonical timing diagnostics must not alter evidence");
            assertTrue(cold.coldStart());
            assertFalse(warm.coldStart());
            assertTrue(cold.initializationNanos() > 0);
            assertEquals(
                SymPyFactorizationPolicy.PINNED_SYMPY_VERSION,
                warm.sympyVersion());
            assertEquals(cold.inputHash(), warm.inputHash());
            assertEquals(cold.scriptHash(), warm.scriptHash());
            assertTrue(cold.outputHash().matches("sha256:[0-9a-f]{64}"));
            assertTrue(warm.outputHash().matches("sha256:[0-9a-f]{64}"));
        }
    }

    @Test
    void aFailedCallDoesNotExposeThePreviousSuccessfulMetrics() {
        FactorizationRequest<BigInteger> successful =
            integerQuarticRequest();
        FactorizationRequest<BigInteger> rejected =
            FactorizationRequest.verifiedDecomposition(
                successful.source(),
                successful.structuralLimits(),
                0,
                successful.maxWorkUnits());

        try (GraalPySymPyFactorizationEngine<BigInteger> engine =
                GraalPySymPyFactorizationEngine.integers()) {
            assertSuccessfulCompleteBackendProposal(
                FactorizationVerifier.execute(engine, successful));
            assertTrue(engine.lastExecutionMetrics().isPresent());

            FactorizationVerifier.Report<BigInteger> report =
                FactorizationVerifier.execute(engine, rejected);

            assertEquals(
                FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
                report.status());
            assertEquals("MAX_CANDIDATES_IS_ZERO", report.detailCode());
            assertTrue(engine.lastExecutionMetrics().isEmpty());
        }
    }

    @Test
    void embeddedRationalEnginePreservesExactFractions() {
        PolynomialRing<ExactRational> ring = new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<ExactRational> source =
            UnivariatePolynomialView.of(
                ring,
                List.of(
                    new ExactRational(
                        BigInteger.valueOf(-1),
                        BigInteger.valueOf(3)),
                    new ExactRational(
                        BigInteger.ONE,
                        BigInteger.valueOf(6)),
                    ExactRational.ONE))
                .toSparsePolynomial();
        FactorizationRequest<ExactRational> request =
            FactorizationRequest.verifiedDecomposition(
                source,
                LIMITS,
                4,
                100_000);

        try (GraalPySymPyFactorizationEngine<ExactRational> engine =
                GraalPySymPyFactorizationEngine.rationals()) {
            FactorizationVerifier.Report<ExactRational> report =
                FactorizationVerifier.execute(engine, request);

            assertSuccessfulCompleteBackendProposal(report);
            assertEquals(
                2,
                report.candidates().getFirst().factors().size());
            assertTrue(report.candidates().getFirst().factors().stream()
                .allMatch(factor -> factor.multiplicity() == 1));
        }
    }

    @Test
    void exactWireContractContainsNoRenderedExpressionOrVariableName() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("name-that-must-not-cross")),
            PolynomialRing.MonomialOrder.GRADED_REVERSE_LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> source =
            UnivariatePolynomialView.of(
                ring,
                List.of(
                    BigInteger.ONE.negate(),
                    BigInteger.ZERO,
                    BigInteger.ONE))
                .toSparsePolynomial();
        FactorizationRequest<BigInteger> request =
            FactorizationRequest.verifiedDecomposition(
                source,
                LIMITS,
                2,
                10_000);

        String payload = SymPyFactorizationCodec.integers()
            .encode(request)
            .payload();

        assertTrue(payload.contains("\"exponents\""));
        assertTrue(payload.contains("\"numerator\""));
        assertFalse(payload.contains("name-that-must-not-cross"));
        assertFalse(payload.contains("expression"));
        assertFalse(payload.contains("sympify"));
    }

    @Test
    void malformedTransportOutputFailsBeforeVerifierCandidateIssuance() {
        SymPyFactorizationEngine<BigInteger> engine =
            new SymPyFactorizationEngine<>(
                "regelsuche.factorization.sympy-malformed-test/v1",
                SymPyFactorizationCodec.integers(),
                SymPyFactorizationPolicy.pinned()) {
                @Override
                SymPyInvocation invoke(String payload) {
                    return SymPyInvocation.completed(
                        "{\"protocol\":\"wrong\"}",
                        "test-runtime",
                        "test-version",
                        true,
                        1,
                        1);
                }
            };

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine,
                integerQuarticRequest());

        assertEquals(
            FactorizationVerifier.Status.TECHNICAL_FAILURE,
            report.status());
        assertEquals("SYMPY_OUTPUT_DECODING_FAILED", report.detailCode());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void oneShotCpythonControlUsesTheSameVerifierBoundary() {
        String python = System.getenv("REGELSUCHE_SYMPY_PYTHON");
        assumeTrue(python != null && !python.isBlank(),
            "Gradle verification provides the pinned CPython environment");
        ProcessSymPyFactorizationEngine<BigInteger> engine =
            ProcessSymPyFactorizationEngine.integers(python);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine,
                integerQuarticRequest());

        assertSuccessfulCompleteBackendProposal(report);
        SymPyExecutionMetrics metrics = engine.lastExecutionMetrics()
            .orElseThrow();
        assertEquals("cpython-one-shot", metrics.runtimeId());
        assertTrue(metrics.coldStart());
        assertEquals(0, metrics.initializationNanos());
    }

    @Test
    void aClosedEmbeddedRuntimeFailsClosed() {
        GraalPySymPyFactorizationEngine<BigInteger> engine =
            GraalPySymPyFactorizationEngine.integers();
        engine.close();

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine,
                integerQuarticRequest());

        assertEquals(
            FactorizationVerifier.Status.TECHNICAL_FAILURE,
            report.status());
        assertEquals("GRAALPY_RUNTIME_CLOSED", report.detailCode());
        assertTrue(report.candidates().isEmpty());
        assertTrue(engine.lastExecutionMetrics().isEmpty());
    }

    private static FactorizationRequest<BigInteger>
            integerQuarticRequest() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(
                new PolynomialVariable("A"),
                new PolynomialVariable("B")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> source = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(4, 0), BigInteger.ONE,
                Monomial.of(0, 4), BigInteger.valueOf(4)));
        return FactorizationRequest.verifiedDecomposition(
            source,
            LIMITS,
            8,
            100_000);
    }

    private static <C> void assertSuccessfulCompleteBackendProposal(
        FactorizationVerifier.Report<C> report
    ) {
        assertEquals(
            FactorizationVerifier.Status.PARTIAL_FACTORIZATION,
            report.status(),
            report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());
        assertEquals(
            FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION,
            report.candidates().getFirst().backendClaim());
        assertTrue(
            report.candidates().getFirst()
                .unresolvedRemainder().isOne());
    }
}
