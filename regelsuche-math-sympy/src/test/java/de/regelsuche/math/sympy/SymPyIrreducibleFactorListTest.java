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

class SymPyIrreducibleFactorListTest {
    @Test
    void trivialFactorListBecomesAnExplicitIrreducibilityClaim() {
        PolynomialRing<BigInteger> ring = new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        FactorizationRequest<BigInteger> request =
            FactorizationRequest.verifiedDecomposition(
                UnivariatePolynomialView.of(
                    ring,
                    List.of(
                        BigInteger.ONE,
                        BigInteger.ZERO,
                        BigInteger.ONE))
                    .toSparsePolynomial(),
                new FactorizationRequest.StructuralLimits(
                    1,
                    4,
                    8,
                    64),
                4,
                100_000);
        SymPyFactorizationEngine<BigInteger> engine =
            new SymPyFactorizationEngine<>(
                "regelsuche.factorization.sympy-irreducible-test/v1",
                SymPyFactorizationCodec.integers(),
                SymPyFactorizationPolicy.pinned()) {
                @Override
                SymPyInvocation invoke(String payload) {
                    return SymPyInvocation.completed(
                        output(),
                        "test-runtime",
                        "test-version",
                        false,
                        1,
                        1);
                }
            };

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(engine, request);

        assertEquals(
            FactorizationVerifier.Status.NO_FACTORIZATION_FOUND,
            report.status(),
            report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .BACKEND_CLAIMED_IRREDUCIBLE,
            report.claimStrength());
        assertEquals(
            "SYMPY_IRREDUCIBLE_FACTOR_LIST",
            report.detailCode());
        assertTrue(report.candidates().isEmpty());
        assertTrue(report.work().units(
            "sympy.classify.trivial-associate-comparisons") > 0);
    }

    private static String output() {
        return """
            {
              "domain": "ZZ",
              "factorNanos": 1,
              "factors": [
                {
                  "multiplicity": 1,
                  "terms": [
                    {
                      "denominator": "1",
                      "exponents": [2],
                      "numerator": "1"
                    },
                    {
                      "denominator": "1",
                      "exponents": [0],
                      "numerator": "1"
                    }
                  ]
                }
              ],
              "protocol": "regelsuche.sympy-factorization/v1",
              "pythonImplementation": "test",
              "pythonVersion": "3.12.8",
              "sympyVersion": "1.14.0",
              "totalNanos": 2,
              "unit": {
                "denominator": "1",
                "numerator": "1"
              }
            }
            """;
    }
}
