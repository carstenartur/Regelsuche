package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExactFactorizationTransformationPipelineTest {
    private static final String ENGINE_ID =
        "regelsuche.test.exact-transformation-engine/v1";
    private static final String ENGINE_CERTIFICATE =
        "sha256:" + "3".repeat(64);
    private static final String RESULT_HASH =
        "sha256:" + "4".repeat(64);

    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void bindsRenderingExactReparseAndPolynomialReconstruction() {
        String sourceText = "1/3*x^2 - 2/3*x + 1/3";
        ExactParsedTerm source = parser.parseExactTerm(sourceText);
        var factorization = factor(
            source,
            squareEngine(),
            1_000_000);
        var pipeline = new ExactFactorizationTransformationPipeline();

        var result = pipeline.transformRoot(source, factorization);
        var repeated = pipeline.transformRoot(
            parser.parseExactTerm(sourceText),
            factorization);

        assertTrue(result.transformed(), result.detailCode());
        assertTrue(result.occurrence().isRoot());
        assertEquals(
            ExactFactorizationTransformationPipeline.Kind
                .VERIFIED_DECOMPOSITION_WITH_COMPLETE_BACKEND_CLAIM,
            result.kind());
        assertEquals(
            "(1 / 3) * (x - 1) ^ 2",
            result.transformedExpression().orElseThrow());
        assertEquals(
            factorization.request().orElseThrow().source(),
            result.reconstruction().orElseThrow()
                .polynomial().orElseThrow());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            factorization.report().orElseThrow().claimStrength());
        assertTrue(
            result.totalWork().units(
                "transform.exact-reparse-input-code-units") > 0);
        assertTrue(
            result.totalWork().units(
                "exact-parsed-view.ast-visits")
                > factorization.totalWork().units(
                    "exact-parsed-view.ast-visits"));
        assertTrue(
            result.totalWork().within(
                factorization.policy().maxTotalWorkUnits()));
        assertEquals(result.canonicalMaterial(), repeated.canonicalMaterial());
        assertTrue(result.certificateHash().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void requiresExplicitSelectionWhenSeveralCandidatesAreVerified() {
        ExactParsedTerm source = parser.parseExactTerm("x^2 - 1");
        var factorization = factor(
            source,
            multipleCandidateEngine(),
            1_000_000);
        var pipeline = new ExactFactorizationTransformationPipeline();

        var implicit = pipeline.transformRoot(source, factorization);
        var explicit = pipeline.transformRoot(source, factorization, 0);

        assertEquals(
            2,
            factorization.report().orElseThrow().candidates().size());
        assertFalse(implicit.transformed());
        assertEquals(
            ExactFactorizationTransformationPipeline.Status.UNSUPPORTED,
            implicit.status());
        assertEquals(
            "MULTIPLE_CANDIDATES_REQUIRE_EXPLICIT_SELECTION",
            implicit.detailCode());
        assertTrue(implicit.candidateIndex().isEmpty());
        assertTrue(implicit.rendering().isEmpty());

        assertTrue(explicit.transformed(), explicit.detailCode());
        assertEquals(0, explicit.candidateIndex().orElseThrow());
        assertEquals(
            factorization.request().orElseThrow().source(),
            explicit.reconstruction().orElseThrow()
                .polynomial().orElseThrow());
    }

    @Test
    void transformsAPartialFactorizationWithAnExactUnresolvedRemainder() {
        ExactParsedTerm source = parser.parseExactTerm(
            "(x - 1) * (x^2 + 1)");
        var factorization = factor(
            source,
            partialEngine(),
            1_000_000);

        var result = new ExactFactorizationTransformationPipeline()
            .transformRoot(source, factorization);

        assertTrue(result.transformed(), result.detailCode());
        assertEquals(
            ExactFactorizationTransformationPipeline.Kind
                .VERIFIED_DECOMPOSITION,
            result.kind());
        assertEquals(
            FactorizationVerifier.ClaimStrength.VERIFIED_DECOMPOSITION,
            factorization.report().orElseThrow().claimStrength());
        assertEquals(
            "(x - 1) * (x ^ 2 + 1)",
            result.transformedExpression().orElseThrow());
        assertEquals(
            factorization.request().orElseThrow().source(),
            result.reconstruction().orElseThrow()
                .polynomial().orElseThrow());
    }

    @Test
    void rejectsSubstitutedSourceEvidenceBeforeRendering() {
        ExactParsedTerm authorized = parser.parseExactTerm(
            "1/3*x^2 - 2/3*x + 1/3");
        var factorization = factor(
            authorized,
            squareEngine(),
            1_000_000);

        var result = new ExactFactorizationTransformationPipeline()
            .transformRoot(
                parser.parseExactTerm("1/3*x^2 - 2/3*x + 2/3"),
                factorization);

        assertFalse(result.transformed());
        assertEquals(
            ExactFactorizationTransformationPipeline.Status
                .SOURCE_EVIDENCE_MISMATCH,
            result.status());
        assertEquals(
            "SOURCE_TEXT_DOES_NOT_MATCH_FACTORIZATION_EVIDENCE",
            result.detailCode());
        assertTrue(result.rendering().isEmpty());
        assertEquals(
            factorization.totalWork(),
            result.totalWork());
    }

    @Test
    void keepsNoCandidateDistinctFromIrreducibility() {
        ExactParsedTerm source = parser.parseExactTerm("x^2 + 1");
        var factorization = factor(
            source,
            noCandidateEngine(),
            1_000_000);

        var result = new ExactFactorizationTransformationPipeline()
            .transformRoot(source, factorization);

        assertFalse(result.transformed());
        assertEquals(
            ExactFactorizationTransformationPipeline.Status.NO_CANDIDATE,
            result.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength.NONE,
            factorization.report().orElseThrow().claimStrength());
        assertTrue(result.transformedExpression().isEmpty());
        assertTrue(result.rendering().isEmpty());
    }

    @Test
    void retainsABackendIrreducibilityClaimWithoutPromotingIt() {
        ExactParsedTerm source = parser.parseExactTerm("x^2 + 1");
        var factorization = factor(
            source,
            irreducibleEngine(),
            1_000_000);

        var result = new ExactFactorizationTransformationPipeline()
            .transformRoot(source, factorization);

        assertFalse(result.transformed());
        assertEquals(
            ExactFactorizationTransformationPipeline.Status
                .BACKEND_CLAIMED_IRREDUCIBLE,
            result.status());
        assertFalse(
            result.status()
                == ExactFactorizationTransformationPipeline.Status.IRREDUCIBLE);
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .BACKEND_CLAIMED_IRREDUCIBLE,
            factorization.report().orElseThrow().claimStrength());
        assertTrue(result.transformedExpression().isEmpty());
        assertTrue(result.rendering().isEmpty());
    }

    @Test
    void doesNotExposeRejectedRenderedSyntaxAsATransformation() {
        ExactParsedTerm source = parser.parseExactTerm(
            "1/3*x^2 - 2/3*x + 1/3");
        var factorization = factor(
            source,
            squareEngine(),
            1_000_000);
        var narrowReparseView = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                1,
                4_096,
                512,
                10_000));
        var pipeline = new ExactFactorizationTransformationPipeline(
            new ExactFactorizationExpressionRenderer(),
            parser,
            narrowReparseView);

        var result = pipeline.transformRoot(source, factorization);

        assertFalse(result.transformed());
        assertEquals(
            ExactFactorizationTransformationPipeline.Status
                .BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals("MAX_DEGREE_EXCEEDED", result.detailCode());
        assertTrue(result.rendering().orElseThrow().rendered());
        assertEquals(
            "(1 / 3) * (x - 1) ^ 2",
            result.renderedExpression().orElseThrow());
        assertTrue(result.transformedExpression().isEmpty());
    }

    @Test
    void refusesToResetTheOriginalAuthorityForRenderingAndReparse() {
        ExactParsedTerm source = parser.parseExactTerm(
            "1/3*x^2 - 2/3*x + 1/3");
        var factorization = factor(
            source,
            squareEngine(),
            20_000);
        assertTrue(factorization.report().orElseThrow().successful());

        var result = new ExactFactorizationTransformationPipeline()
            .transformRoot(source, factorization);

        assertFalse(result.transformed());
        assertEquals(
            ExactFactorizationTransformationPipeline.Status
                .BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "INSUFFICIENT_REMAINING_TRANSFORMATION_AUTHORITY",
            result.detailCode());
        assertEquals(factorization.totalWork(), result.totalWork());
        assertTrue(result.rendering().isEmpty());
    }

    private ExactParsedFactorizationPipeline.Result factor(
        ExactParsedTerm source,
        FactorizationEngine<ExactRational> engine,
        long maxTotalWorkUnits
    ) {
        var policy = new ExactParsedFactorizationPipeline.Policy(
            new FactorizationRequest.StructuralLimits(
                1,
                64,
                65,
                8_192),
            16,
            maxTotalWorkUnits,
            FactorizationRequest.EvidenceRequirement
                .VERIFIED_DECOMPOSITION);
        return new ExactParsedFactorizationPipeline(
            new ExactParsedUnivariatePolynomialView(),
            policy).factor(source, engine);
    }

    private static FactorizationEngine<ExactRational> squareEngine() {
        return new FactorizationEngine<>() {
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
                FactorizationRequest<ExactRational> request
            ) {
                PolynomialRing<ExactRational> ring = request.source().ring();
                SparsePolynomial<ExactRational> factor =
                    polynomial(
                        ring,
                        Map.of(
                            Monomial.of(1), ExactRational.ONE,
                            Monomial.of(0), ExactRational.NEGATIVE_ONE));
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.CANDIDATES,
                    "TEST_COMPLETE_SQUARE_FACTORIZATION",
                    new PolynomialWorkLedger(Map.of("test.engine", 1L)),
                    List.of(new Proposal<>(
                        rational(1, 3),
                        List.of(new PolynomialFactor<>(factor, 2)),
                        SparsePolynomial.one(ring),
                        ENGINE_CERTIFICATE)),
                    BackendClaim.COMPLETE_FACTORIZATION,
                    RESULT_HASH);
            }
        };
    }

    private static FactorizationEngine<ExactRational>
            multipleCandidateEngine() {
        return new FactorizationEngine<>() {
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
                FactorizationRequest<ExactRational> request
            ) {
                PolynomialRing<ExactRational> ring = request.source().ring();
                SparsePolynomial<ExactRational> xMinusOne =
                    polynomial(
                        ring,
                        Map.of(
                            Monomial.of(1), ExactRational.ONE,
                            Monomial.of(0), ExactRational.NEGATIVE_ONE));
                SparsePolynomial<ExactRational> xPlusOne =
                    polynomial(
                        ring,
                        Map.of(
                            Monomial.of(1), ExactRational.ONE,
                            Monomial.of(0), ExactRational.ONE));
                SparsePolynomial<ExactRational> oneMinusX =
                    polynomial(
                        ring,
                        Map.of(
                            Monomial.of(1), ExactRational.NEGATIVE_ONE,
                            Monomial.of(0), ExactRational.ONE));
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.CANDIDATES,
                    "TEST_MULTIPLE_EQUIVALENT_FACTORIZATIONS",
                    new PolynomialWorkLedger(Map.of("test.engine", 1L)),
                    List.of(
                        new Proposal<>(
                            ExactRational.ONE,
                            List.of(
                                new PolynomialFactor<>(xMinusOne, 1),
                                new PolynomialFactor<>(xPlusOne, 1)),
                            SparsePolynomial.one(ring),
                            ENGINE_CERTIFICATE),
                        new Proposal<>(
                            ExactRational.NEGATIVE_ONE,
                            List.of(
                                new PolynomialFactor<>(oneMinusX, 1),
                                new PolynomialFactor<>(xPlusOne, 1)),
                            SparsePolynomial.one(ring),
                            ENGINE_CERTIFICATE)),
                    BackendClaim.NONE,
                    RESULT_HASH);
            }
        };
    }

    private static FactorizationEngine<ExactRational> partialEngine() {
        return new FactorizationEngine<>() {
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
                FactorizationRequest<ExactRational> request
            ) {
                PolynomialRing<ExactRational> ring = request.source().ring();
                SparsePolynomial<ExactRational> factor =
                    polynomial(
                        ring,
                        Map.of(
                            Monomial.of(1), ExactRational.ONE,
                            Monomial.of(0), ExactRational.NEGATIVE_ONE));
                SparsePolynomial<ExactRational> remainder =
                    polynomial(
                        ring,
                        Map.of(
                            Monomial.of(2), ExactRational.ONE,
                            Monomial.of(0), ExactRational.ONE));
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.CANDIDATES,
                    "TEST_PARTIAL_FACTORIZATION",
                    new PolynomialWorkLedger(Map.of("test.engine", 1L)),
                    List.of(new Proposal<>(
                        ExactRational.ONE,
                        List.of(new PolynomialFactor<>(factor, 1)),
                        remainder,
                        ENGINE_CERTIFICATE)),
                    BackendClaim.NONE,
                    RESULT_HASH);
            }
        };
    }

    private static FactorizationEngine<ExactRational> noCandidateEngine() {
        return new FactorizationEngine<>() {
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
                FactorizationRequest<ExactRational> request
            ) {
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.NO_CANDIDATE,
                    "TEST_NO_FACTORIZATION_FOUND",
                    new PolynomialWorkLedger(Map.of("test.engine", 1L)),
                    List.of(),
                    BackendClaim.NONE,
                    RESULT_HASH);
            }
        };
    }

    private static FactorizationEngine<ExactRational> irreducibleEngine() {
        return new FactorizationEngine<>() {
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
                FactorizationRequest<ExactRational> request
            ) {
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.NO_CANDIDATE,
                    "TEST_BACKEND_IRREDUCIBLE",
                    new PolynomialWorkLedger(Map.of("test.engine", 1L)),
                    List.of(),
                    BackendClaim.IRREDUCIBLE,
                    RESULT_HASH);
            }
        };
    }

    private static SparsePolynomial<ExactRational> polynomial(
        PolynomialRing<ExactRational> ring,
        Map<Monomial, ExactRational> terms
    ) {
        return new SparsePolynomial<>(ring, terms);
    }

    private static ExactRational rational(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }
}
