package de.regelsuche.math.sympy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class SymPyFailureDiagnosticTest {
    @Test
    void transportDiagnosticsAreObservableButExcludedFromEvidence() {
        SymPyFactorizationEngine<BigInteger> firstEngine =
            failingEngine("first runtime detail");
        SymPyFactorizationEngine<BigInteger> secondEngine =
            failingEngine("second runtime detail");

        FactorizationVerifier.Report<BigInteger> first =
            FactorizationVerifier.execute(firstEngine, request());
        FactorizationVerifier.Report<BigInteger> second =
            FactorizationVerifier.execute(secondEngine, request());

        assertEquals(first, second);
        assertEquals(
            "first runtime detail",
            firstEngine.lastFailureDiagnostic().orElseThrow());
        assertEquals(
            "second runtime detail",
            secondEngine.lastFailureDiagnostic().orElseThrow());
    }

    @Test
    void localDecoderFailuresRetainTheirBoundedCause() {
        SymPyFactorizationEngine<BigInteger> engine =
            new SymPyFactorizationEngine<>(
                "regelsuche.factorization.sympy-diagnostic-test/v1",
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
            FactorizationVerifier.execute(engine, request());

        assertEquals(
            FactorizationVerifier.Status.TECHNICAL_FAILURE,
            report.status());
        assertEquals(
            "SYMPY_OUTPUT_DECODING_FAILED",
            report.detailCode());
        String diagnostic = engine.lastFailureDiagnostic().orElseThrow();
        assertTrue(diagnostic.contains(
            "java.lang.IllegalArgumentException"));
        assertTrue(diagnostic.contains("SymPy protocol mismatch"));
    }

    private static SymPyFactorizationEngine<BigInteger> failingEngine(
        String diagnostic
    ) {
        return new SymPyFactorizationEngine<>(
            "regelsuche.factorization.sympy-diagnostic-test/v1",
            SymPyFactorizationCodec.integers(),
            SymPyFactorizationPolicy.pinned()) {
            @Override
            SymPyInvocation invoke(String payload) {
                return SymPyInvocation.failure(
                    SymPyInvocation.Status.TECHNICAL_FAILURE,
                    "TEST_TRANSPORT_FAILURE",
                    "test-runtime",
                    1,
                    diagnostic);
            }
        };
    }

    private static FactorizationRequest<BigInteger> request() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        return FactorizationRequest.verifiedDecomposition(
            UnivariatePolynomialView.of(
                ring,
                List.of(
                    BigInteger.ONE.negate(),
                    BigInteger.ZERO,
                    BigInteger.ONE))
                .toSparsePolynomial(),
            new FactorizationRequest.StructuralLimits(
                1,
                2,
                3,
                8),
            1,
            100);
    }
}
