package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactParsedFactorizationPipeline;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class ExactParsedNativeFactorizationIntegrationTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void factorsOneExactSourceBoundRationalPolynomialEndToEnd() {
        var pipeline = new ExactParsedFactorizationPipeline();
        var result = pipeline.factor(
            parser.parseExactTerm("1/2*x^2 - 1/2"),
            NativeUnivariateFactorizationEngine.boundedRationals());

        assertTrue(result.executed(), result.detailCode());
        FactorizationVerifier.Report<ExactRational> report =
            result.report().orElseThrow();
        assertTrue(report.successful(), report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());

        var candidate = report.candidates().getFirst();
        assertEquals(rational(1, 2), candidate.unit());
        assertEquals(2, candidate.factors().size());
        assertTrue(candidate.unresolvedRemainder().isOne());

        SparsePolynomial<ExactRational> source =
            result.extraction().polynomial().orElseThrow();
        assertSame(source, result.request().orElseThrow().source());
        SparsePolynomial<ExactRational> reconstructed =
            SparsePolynomial.constant(source.ring(), candidate.unit());
        for (PolynomialFactor<ExactRational> factor : candidate.factors()) {
            reconstructed = reconstructed.multiply(
                factor.polynomial().pow(factor.multiplicity()));
        }
        assertEquals(source, reconstructed);
        assertTrue(
            result.totalWork().within(
                result.policy().maxTotalWorkUnits()));
        assertTrue(
            result.totalWork().units(
                "exact-parsed-view.ast-visits") > 0);
        assertTrue(
            result.certificateHash().matches("sha256:[0-9a-f]{64}"));
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
