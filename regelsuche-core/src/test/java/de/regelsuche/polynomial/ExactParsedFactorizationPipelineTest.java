package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExactParsedFactorizationPipelineTest {
    private static final String ENGINE_ID =
        "regelsuche.test.exact-rational-factorization-engine/v1";
    private static final String RESULT_HASH = "sha256:" + "0".repeat(64);

    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void createsOneTypedRequestWithOnlyTheRemainingWorkAuthority() {
        var policy = new ExactParsedFactorizationPipeline.Policy(
            new FactorizationRequest.StructuralLimits(1, 16, 17, 4_096),
            25,
            20_000,
            FactorizationRequest.EvidenceRequirement
                .VERIFIED_DECOMPOSITION);
        var pipeline = new ExactParsedFactorizationPipeline(
            new ExactParsedUnivariatePolynomialView(),
            policy);
        AtomicReference<FactorizationRequest<ExactRational>> received =
            new AtomicReference<>();
        FactorizationEngine<ExactRational> engine = engine(
            received,
            new AtomicInteger());

        var result = pipeline.factor(
            parser.parseExactTerm("x^2 - 1"),
            engine);

        assertTrue(result.executed());
        assertEquals(
            FactorizationVerifier.Status.UNSUPPORTED_REQUEST,
            result.report().orElseThrow().status());
        FactorizationRequest<ExactRational> request = received.get();
        assertSame(request, result.request().orElseThrow());
        assertEquals(
            result.extraction().polynomial().orElseThrow(),
            request.source());
        assertEquals(
            policy.maxTotalWorkUnits()
                - result.extraction().work().totalWorkUnits(),
            request.maxWorkUnits());
        assertEquals(
            result.extraction().work().totalWorkUnits() + 3,
            result.totalWork().totalWorkUnits());
        assertTrue(result.totalWork().within(policy.maxTotalWorkUnits()));
        assertTrue(
            result.certificateHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(
            result.canonicalMaterial(),
            pipeline.factor(
                parser.parseExactTerm("x^2 - 1"),
                engine(new AtomicReference<>(), new AtomicInteger()))
                .canonicalMaterial());
    }

    @Test
    void neverInvokesAnEngineForUnsupportedOrTrivialSources() {
        AtomicInteger invocations = new AtomicInteger();
        FactorizationEngine<ExactRational> engine = engine(
            new AtomicReference<>(),
            invocations);
        var pipeline = new ExactParsedFactorizationPipeline();

        var unsupported = pipeline.factor(
            parser.parseExactTerm("x + y"),
            engine);
        var zero = pipeline.factor(
            parser.parseExactTerm("0"),
            engine);
        var constant = pipeline.factor(
            parser.parseExactTerm("2"),
            engine);

        assertEquals(
            ExactParsedFactorizationPipeline.Status.UNSUPPORTED_EXPRESSION,
            unsupported.status());
        assertEquals(
            ExactParsedFactorizationPipeline.Status.UNSUPPORTED_REQUEST,
            zero.status());
        assertEquals(
            "ZERO_POLYNOMIAL_HAS_NO_FINITE_FACTORIZATION_CONTRACT",
            zero.detailCode());
        assertEquals(
            ExactParsedFactorizationPipeline.Status.UNSUPPORTED_REQUEST,
            constant.status());
        assertEquals(
            "CONSTANT_POLYNOMIAL_HAS_NO_NONTRIVIAL_FACTORIZATION_REQUEST",
            constant.detailCode());
        assertEquals(0, invocations.get());
        assertTrue(unsupported.report().isEmpty());
        assertTrue(zero.request().isEmpty());
        assertTrue(constant.report().isEmpty());
    }

    @Test
    void rejectsAConfigurationWhoseExtractionCeilingExceedsTotalAuthority() {
        var view = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                16,
                4_096,
                3,
                2));
        var policy = new ExactParsedFactorizationPipeline.Policy(
            new FactorizationRequest.StructuralLimits(1, 16, 17, 4_096),
            1,
            4,
            FactorizationRequest.EvidenceRequirement
                .VERIFIED_DECOMPOSITION);

        var exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ExactParsedFactorizationPipeline(view, policy));

        assertEquals(
            "PIPELINE_TOTAL_WORK_BELOW_EXTRACTION_CEILING",
            exception.getMessage());
    }

    @Test
    void refusesToResetWorkWhenExtractionConsumesTheAuthority() {
        var view = new ExactParsedUnivariatePolynomialView(
            new ExactParsedUnivariatePolynomialView.Budget(
                16,
                4_096,
                3,
                2));
        var policy = new ExactParsedFactorizationPipeline.Policy(
            new FactorizationRequest.StructuralLimits(1, 16, 17, 4_096),
            1,
            5,
            FactorizationRequest.EvidenceRequirement
                .VERIFIED_DECOMPOSITION);
        var pipeline = new ExactParsedFactorizationPipeline(view, policy);
        AtomicInteger invocations = new AtomicInteger();

        var result = pipeline.factor(
            parser.parseExactTerm("x + 1"),
            engine(new AtomicReference<>(), invocations));

        assertEquals(
            ExactParsedFactorizationPipeline.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "NO_FACTORIZATION_WORK_BUDGET_REMAINING",
            result.detailCode());
        assertEquals(5, result.totalWork().totalWorkUnits());
        assertEquals(0, invocations.get());
        assertFalse(result.executed());
    }

    private static FactorizationEngine<ExactRational> engine(
        AtomicReference<FactorizationRequest<ExactRational>> received,
        AtomicInteger invocations
    ) {
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
                received.set(request);
                invocations.incrementAndGet();
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.UNSUPPORTED_REQUEST,
                    "TEST_ENGINE_DECLINED_REQUEST",
                    new PolynomialWorkLedger(Map.of("test.engine", 3L)),
                    List.of(),
                    BackendClaim.NONE,
                    RESULT_HASH);
            }
        };
    }
}
