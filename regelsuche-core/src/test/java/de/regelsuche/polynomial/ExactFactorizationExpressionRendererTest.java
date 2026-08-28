package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.scalar.ExactRationalDomain;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactFactorizationExpressionRendererTest {
    private static final String ENGINE_ID =
        "regelsuche.test.rendering-engine/v1";
    private static final String ENGINE_CERTIFICATE =
        "sha256:" + "1".repeat(64);
    private static final String RESULT_HASH =
        "sha256:" + "2".repeat(64);

    private final ExpressionParser parser = new ExpressionParser();
    private final ExactParsedUnivariatePolynomialView view =
        new ExactParsedUnivariatePolynomialView();

    @Test
    void rendersExactRationalsAndMultiplicitiesDeterministically() {
        SparsePolynomial<ExactRational> factor = polynomial("x - 1");
        SparsePolynomial<ExactRational> source = factor.pow(2).scale(
            rational(1, 3));
        var candidate = verifiedCandidate(
            source,
            rational(1, 3),
            List.of(new PolynomialFactor<>(factor, 2)),
            SparsePolynomial.one(source.ring()),
            FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION);
        var renderer = new ExactFactorizationExpressionRenderer();

        var first = renderer.render(candidate);
        var second = renderer.render(candidate);

        assertTrue(first.rendered(), first.detailCode());
        assertEquals("(1 / 3) * (x - 1) ^ 2", first.expression().orElseThrow());
        assertEquals(first.canonicalMaterial(), second.canonicalMaterial());
        assertTrue(first.work().units("render.output-code-units") > 0);
        assertTrue(
            first.work().units("render.inspected-polynomial-terms") > 0);
        assertTrue(first.work().units("render.inspected-coefficients") > 0);
        assertTrue(first.certificateHash().matches("sha256:[0-9a-f]{64}"));

        var reconstructed = view.analyze(
            parser.parseExactTerm(first.expression().orElseThrow()));
        assertTrue(reconstructed.supported(), reconstructed.detailCode());
        assertEquals(source, reconstructed.polynomial().orElseThrow());
    }

    @Test
    void rendersNegativeUnitsAndNonmonicRationalFactorsExactly() {
        SparsePolynomial<ExactRational> factor =
            polynomial("2*x + 3/5");
        SparsePolynomial<ExactRational> source = factor.scale(
            rational(-1, 2));
        var candidate = verifiedCandidate(
            source,
            rational(-1, 2),
            List.of(new PolynomialFactor<>(factor, 1)),
            SparsePolynomial.one(source.ring()),
            FactorizationEngine.BackendClaim.NONE);

        var result = new ExactFactorizationExpressionRenderer()
            .render(candidate);

        assertTrue(result.rendered(), result.detailCode());
        assertEquals(
            "(-1 / 2) * (2 * x + (3 / 5))",
            result.expression().orElseThrow());
        var reconstructed = view.analyze(
            parser.parseExactTerm(result.expression().orElseThrow()));
        assertEquals(source, reconstructed.polynomial().orElseThrow());
    }

    @Test
    void rendersAnExactUnresolvedRemainderWithoutClaimingCompleteness() {
        SparsePolynomial<ExactRational> factor = polynomial("x - 1");
        SparsePolynomial<ExactRational> remainder = polynomial("x^2 + 1");
        SparsePolynomial<ExactRational> source = factor.multiply(remainder);
        var candidate = verifiedCandidate(
            source,
            ExactRational.ONE,
            List.of(new PolynomialFactor<>(factor, 1)),
            remainder,
            FactorizationEngine.BackendClaim.NONE);

        var result = new ExactFactorizationExpressionRenderer()
            .render(candidate);

        assertTrue(result.rendered(), result.detailCode());
        assertEquals(
            "(x - 1) * (x ^ 2 + 1)",
            result.expression().orElseThrow());
        assertEquals(
            1,
            result.work().units("render.unresolved-remainders"));
        var reconstructed = view.analyze(
            parser.parseExactTerm(result.expression().orElseThrow()));
        assertEquals(source, reconstructed.polynomial().orElseThrow());
    }

    @Test
    void rejectsTheTermLimitBeforeInspectingAnOversizedPolynomial() {
        SparsePolynomial<ExactRational> factor = polynomial("x - 1");
        SparsePolynomial<ExactRational> source = factor.pow(2);
        var candidate = verifiedCandidate(
            source,
            ExactRational.ONE,
            List.of(new PolynomialFactor<>(factor, 2)),
            SparsePolynomial.one(source.ring()),
            FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION);
        var renderer = new ExactFactorizationExpressionRenderer(
            new ExactFactorizationExpressionRenderer.Policy(
                10,
                1,
                64,
                8_192,
                100,
                1_000));

        var result = renderer.render(candidate);

        assertFalse(result.rendered());
        assertEquals(
            ExactFactorizationExpressionRenderer.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "MAX_POLYNOMIAL_TERMS_EXCEEDED",
            result.detailCode());
        assertEquals(
            0,
            result.work().units("render.inspected-polynomial-terms"));
        assertEquals(
            1,
            result.work().units("render.inspected-coefficients"));
        assertTrue(result.expression().isEmpty());
    }

    @Test
    void rejectsCoefficientsThatTheExactParserCannotReparse() {
        SparsePolynomial<ExactRational> factor = polynomial("x - 1");
        ExactRational oversized = new ExactRational(
            BigInteger.TEN.pow(ExactRationalDomain.MAX_DIGITS),
            BigInteger.ONE);
        SparsePolynomial<ExactRational> source = factor.scale(oversized);
        var candidate = verifiedCandidate(
            source,
            oversized,
            List.of(new PolynomialFactor<>(factor, 1)),
            SparsePolynomial.one(source.ring()),
            FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION);
        var renderer = new ExactFactorizationExpressionRenderer();

        assertTrue(
            ExactRationalField.INSTANCE.bitLength(oversized)
                <= renderer.policy().maxCoefficientBits());

        var result = renderer.render(candidate);

        assertFalse(result.rendered());
        assertEquals(
            ExactFactorizationExpressionRenderer.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "MAX_COEFFICIENT_DIGITS_EXCEEDED",
            result.detailCode());
        assertTrue(result.expression().isEmpty());
    }

    @Test
    void failsClosedWhenTheOutputRepresentationLimitIsExceeded() {
        SparsePolynomial<ExactRational> factor = polynomial("x - 1");
        SparsePolynomial<ExactRational> source = factor.pow(2).scale(
            rational(1, 3));
        var candidate = verifiedCandidate(
            source,
            rational(1, 3),
            List.of(new PolynomialFactor<>(factor, 2)),
            SparsePolynomial.one(source.ring()),
            FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION);
        var renderer = new ExactFactorizationExpressionRenderer(
            new ExactFactorizationExpressionRenderer.Policy(
                10,
                10,
                64,
                8_192,
                10,
                100));

        var result = renderer.render(candidate);

        assertFalse(result.rendered());
        assertEquals(
            ExactFactorizationExpressionRenderer.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals("MAX_OUTPUT_CODE_UNITS_EXCEEDED", result.detailCode());
        assertTrue(result.expression().isEmpty());
    }

    @Test
    void rejectsVerifierIssuedCandidatesOutsideTheUnivariateRing() {
        PolynomialRing<ExactRational> ring = new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(
                new PolynomialVariable("x"),
                new PolynomialVariable("y")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<ExactRational> source = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(1, 0), ExactRational.ONE,
                Monomial.of(0, 1), ExactRational.ONE));
        var candidate = verifiedCandidate(
            source,
            ExactRational.ONE,
            List.of(new PolynomialFactor<>(source, 1)),
            SparsePolynomial.one(ring),
            FactorizationEngine.BackendClaim.NONE);

        var result = new ExactFactorizationExpressionRenderer()
            .render(candidate);

        assertFalse(result.rendered());
        assertEquals(
            ExactFactorizationExpressionRenderer.Status.UNSUPPORTED,
            result.status());
        assertEquals("RENDERER_REQUIRES_UNIVARIATE_RING", result.detailCode());
    }

    private SparsePolynomial<ExactRational> polynomial(String source) {
        var analysis = view.analyze(parser.parseExactTerm(source));
        assertTrue(analysis.supported(), analysis.detailCode());
        return analysis.polynomial().orElseThrow();
    }

    private static FactorizationVerifier.VerifiedCandidate<ExactRational>
            verifiedCandidate(
                SparsePolynomial<ExactRational> source,
                ExactRational unit,
                List<PolynomialFactor<ExactRational>> factors,
                SparsePolynomial<ExactRational> remainder,
                FactorizationEngine.BackendClaim claim
            ) {
        FactorizationRequest<ExactRational> request =
            new FactorizationRequest<>(
                source,
                FactorizationRequest.EvidenceRequirement
                    .VERIFIED_DECOMPOSITION,
                new FactorizationRequest.StructuralLimits(
                    Math.max(1, source.ring().variableCount()),
                    Math.max(0, source.totalDegree()),
                    Math.max(1, source.termCount()),
                    Math.max(1, source.maxCoefficientBitLength())),
                4,
                10_000);
        FactorizationEngine<ExactRational> engine = new FactorizationEngine<>() {
            @Override
            public String engineId() {
                return ENGINE_ID;
            }

            @Override
            public String coefficientDomainId() {
                return ExactRationalField.DOMAIN_ID;
            }

            @Override
            public EngineResult<ExactRational> propose(
                FactorizationRequest<ExactRational> ignored
            ) {
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.CANDIDATES,
                    "TEST_CANDIDATE",
                    PolynomialWorkLedger.empty(),
                    List.of(new Proposal<>(
                        unit,
                        factors,
                        remainder,
                        ENGINE_CERTIFICATE)),
                    claim,
                    RESULT_HASH);
            }
        };
        FactorizationVerifier.Report<ExactRational> report =
            FactorizationVerifier.execute(engine, request);
        assertTrue(report.successful(), report.toString());
        assertEquals(1, report.candidates().size());
        return report.candidates().getFirst();
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
