package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.scalar.ExactRational;
import java.util.LinkedHashMap;
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

    @Test
    void sharedDispatchRejectionRetainsExtractionWithoutInvokingTheEngine() {
        var authority = new RejectingAuthority("factorization.request-dispatch");
        var pipeline = new ExactParsedFactorizationPipeline(new ExactParsedUnivariatePolynomialView(),
            ExactParsedFactorizationPipeline.Policy.boundedDefaults(), authority);
        var invocations = new AtomicInteger();
        var result = pipeline.factor(parser.parseExactTerm("x^2 - 1"),
            engine(new AtomicReference<>(), invocations));

        assertEquals(ExactParsedFactorizationPipeline.Status.BUDGET_INCONCLUSIVE, result.status());
        assertEquals("SHARED_POLYNOMIAL_WORK_AUTHORITY_EXHAUSTED", result.detailCode());
        assertEquals(0, invocations.get());
        assertTrue(result.request().isEmpty());
        assertTrue(result.report().isEmpty());
        assertEquals(result.extraction().work().asPolynomialWorkLedger(), result.totalWork());
        assertEquals(result.totalWork(), authority.ledger);
        assertEquals(authority.opaqueInvocationOverhead(), authority.rejectedWork);
        assertFalse(authority.ledger.stages().containsKey("factorization.request-dispatch"));
    }

    @Test
    void rejectionOfAlreadyExecutedOpaqueWorkIsAnAuthorityInvariantFailure() {
        var authority = new RejectingAuthority("test.engine");
        var pipeline = new ExactParsedFactorizationPipeline(new ExactParsedUnivariatePolynomialView(),
            ExactParsedFactorizationPipeline.Policy.boundedDefaults(), authority);
        var invocations = new AtomicInteger();
        var received = new AtomicReference<FactorizationRequest<ExactRational>>();

        var exception = assertThrows(IllegalStateException.class, () -> pipeline.factor(
            parser.parseExactTerm("x^2 - 1"), engine(received, invocations)));

        assertEquals("OPAQUE_FACTORIZATION_WORK_REJECTED_BY_SHARED_AUTHORITY", exception.getMessage());
        assertTrue(exception.getCause() instanceof PolynomialWorkAuthority.LimitReached);
        assertEquals(1, invocations.get());
        assertEquals(99, received.get().maxWorkUnits());
        assertEquals(new PolynomialWorkLedger(Map.of("test.engine", 3L)), authority.rejectedWork);
        assertTrue(authority.rejectedWork.within(received.get().maxWorkUnits()));
        assertEquals(1L, authority.ledger.stages().get("factorization.request-dispatch"));
        assertFalse(authority.ledger.stages().containsKey("test.engine"));
    }

    private static final class RejectingAuthority implements PolynomialWorkAuthority {
        private final String rejectedStage;
        private PolynomialWorkLedger ledger = PolynomialWorkLedger.empty();
        private PolynomialWorkLedger rejectedWork;

        private RejectingAuthority(String rejectedStage) {
            this.rejectedStage = rejectedStage;
        }

        @Override
        public void consume(PolynomialWorkLedger work) {
            if (work.stages().containsKey(rejectedStage)) {
                rejectedWork = work;
                throw new LimitReached();
            }
            var stages = new LinkedHashMap<>(ledger.stages());
            work.stages().forEach((stage, units) -> stages.merge(stage, units, Math::addExact));
            ledger = new PolynomialWorkLedger(stages);
        }

        @Override
        public long remainingOpaqueWorkUnits() {
            return 100;
        }

        @Override
        public PolynomialWorkLedger opaqueInvocationOverhead() {
            return new PolynomialWorkLedger(Map.of("factorization.request-dispatch", 1L));
        }
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
