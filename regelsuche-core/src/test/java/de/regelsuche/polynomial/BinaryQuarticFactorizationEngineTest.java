package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BinaryQuarticFactorizationEngineTest {
    private final PolynomialRing<BigInteger> ring = new PolynomialRing<>(
        BigIntegerDomain.INSTANCE,
        List.of(
            new PolynomialVariable("A"),
            new PolynomialVariable("B")),
        PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            2,
            4,
            32,
            4_096);
    private final BinaryQuarticFactorizationEngine engine =
        new BinaryQuarticFactorizationEngine();

    @Test
    void verifierIssuesExactDecompositionsWithoutClaimingIrreducibility() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.valueOf(4));

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine,
                request(source, 8, 4_096));

        assertEquals(
            FactorizationVerifier.Status.PARTIAL_FACTORIZATION,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength.VERIFIED_DECOMPOSITION,
            report.claimStrength());
        assertTrue(report.successful());
        assertFalse(report.candidates().isEmpty());
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.unresolvedRemainder().isOne()
                && candidate.verificationCertificateHash()
                    .matches("sha256:[0-9a-f]{64}")));
        assertTrue(report.work().units(
            "verify.product-comparisons") > 0);
        assertTrue(report.work().totalWorkUnits() <= 4_096);
    }

    @Test
    void aTemplateMissIsNotReportedAsIrreducibility() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.valueOf(2));

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine,
                request(source, 8, 4_096));

        assertEquals(
            FactorizationVerifier.Status.NO_FACTORIZATION_FOUND,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength.NONE,
            report.claimStrength());
        assertFalse(report.successful());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void strongerCompletenessAndExhaustedBudgetsFailClosed() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.valueOf(4));
        FactorizationVerifier.Report<BigInteger> completeness =
            FactorizationVerifier.execute(
                engine,
                new FactorizationRequest<>(
                    source,
                    FactorizationRequest.EvidenceRequirement
                        .INDEPENDENT_COMPLETE,
                    limits,
                    8,
                    4_096));
        FactorizationVerifier.Report<BigInteger> zeroCandidates =
            FactorizationVerifier.execute(
                engine,
                request(source, 0, 4_096));
        FactorizationVerifier.Report<BigInteger> work =
            FactorizationVerifier.execute(
                new BinaryQuarticFactorizationEngine(32, 1),
                request(source, 8, 1));

        assertEquals(
            FactorizationVerifier.Status.UNSUPPORTED_REQUEST,
            completeness.status());
        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            zeroCandidates.status());
        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            work.status());
        assertTrue(work.work().totalWorkUnits() <= 1);
    }

    @Test
    void enginesCannotManufactureVerifiedOrCompleteEvidence() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.valueOf(4));
        FactorizationEngine<BigInteger> lyingEngine = engineReturning(
            source,
            validSophieProposal(source),
            FactorizationEngine.BackendClaim.COMPLETE_FACTORIZATION);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                lyingEngine,
                request(source, 4, 64));

        assertEquals(
            FactorizationVerifier.Status.PARTIAL_FACTORIZATION,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(
            0,
            FactorizationVerifier.VerifiedCandidate.class
                .getConstructors()
                .length);
        assertEquals(
            0,
            FactorizationVerifier.Report.class
                .getConstructors()
                .length);
    }

    @Test
    void verifierRejectsAProposalWhoseProductDoesNotMatch() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.ONE,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.ZERO,
            BigInteger.valueOf(4));
        SparsePolynomial<BigInteger> wrongFactor = new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(1, 0), BigInteger.ONE,
                Monomial.of(0, 1), BigInteger.ONE));
        FactorizationEngine.Proposal<BigInteger> wrong =
            new FactorizationEngine.Proposal<>(
                BigInteger.ONE,
                List.of(new PolynomialFactor<>(wrongFactor, 1)),
                SparsePolynomial.one(ring),
                "sha256:" + "b".repeat(64));

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engineReturning(
                    source,
                    wrong,
                    FactorizationEngine.BackendClaim.NONE),
                request(source, 4, 64));

        assertEquals(
            FactorizationVerifier.Status.TECHNICAL_FAILURE,
            report.status());
        assertEquals(
            "FACTORIZATION_PROPOSAL_PRODUCT_MISMATCH",
            report.detailCode());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void integerFactorContentsMoveIntoTheCanonicalUnit() {
        SparsePolynomial<BigInteger> source = quartic(
            BigInteger.valueOf(4),
            BigInteger.ZERO,
            BigInteger.valueOf(12),
            BigInteger.ZERO,
            BigInteger.valueOf(8));

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine,
                request(source, 16, 4_096));

        assertTrue(report.successful(), report.toString());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            candidate.unit().abs().compareTo(BigInteger.ONE) > 0));
        assertTrue(report.candidates().stream()
            .flatMap(candidate -> candidate.factors().stream())
            .allMatch(factor -> coefficientContent(
                factor.polynomial()).equals(BigInteger.ONE)));
    }

    private FactorizationRequest<BigInteger> request(
        SparsePolynomial<BigInteger> source,
        int maxCandidates,
        long maxWork
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            limits,
            maxCandidates,
            maxWork);
    }

    private FactorizationEngine<BigInteger> engineReturning(
        SparsePolynomial<BigInteger> source,
        FactorizationEngine.Proposal<BigInteger> proposal,
        FactorizationEngine.BackendClaim backendClaim
    ) {
        return new FactorizationEngine<>() {
            @Override
            public String engineId() {
                return "test.factorization-engine/v1";
            }

            @Override
            public String coefficientDomainId() {
                return BigIntegerDomain.DOMAIN_ID;
            }

            @Override
            public EngineResult<BigInteger> propose(
                FactorizationRequest<BigInteger> request
            ) {
                assertEquals(source, request.source());
                return new EngineResult<>(
                    engineId(),
                    Outcome.CANDIDATES,
                    "TEST_ENGINE_PROPOSAL",
                    PolynomialWorkLedger.empty(),
                    List.of(proposal),
                    backendClaim,
                    "sha256:" + "c".repeat(64));
            }
        };
    }

    private FactorizationEngine.Proposal<BigInteger> validSophieProposal(
        SparsePolynomial<BigInteger> source
    ) {
        SparsePolynomial<BigInteger> left = quadratic(1, -2, 2);
        SparsePolynomial<BigInteger> right = quadratic(1, 2, 2);
        return new FactorizationEngine.Proposal<>(
            BigInteger.ONE,
            List.of(
                new PolynomialFactor<>(left, 1),
                new PolynomialFactor<>(right, 1)),
            SparsePolynomial.one(source.ring()),
            "sha256:" + "a".repeat(64));
    }

    private SparsePolynomial<BigInteger> quadratic(
        int a,
        int b,
        int c
    ) {
        return new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(2, 0), BigInteger.valueOf(a),
                Monomial.of(1, 1), BigInteger.valueOf(b),
                Monomial.of(0, 2), BigInteger.valueOf(c)));
    }

    private static BigInteger coefficientContent(
        SparsePolynomial<BigInteger> polynomial
    ) {
        BigInteger result = BigInteger.ZERO;
        for (BigInteger coefficient : polynomial.terms().values()) {
            result = result.gcd(coefficient.abs());
        }
        return result;
    }

    private SparsePolynomial<BigInteger> quartic(
        BigInteger c40,
        BigInteger c31,
        BigInteger c22,
        BigInteger c13,
        BigInteger c04
    ) {
        return new SparsePolynomial<>(
            ring,
            Map.of(
                Monomial.of(4, 0), c40,
                Monomial.of(3, 1), c31,
                Monomial.of(2, 2), c22,
                Monomial.of(1, 3), c13,
                Monomial.of(0, 4), c04));
    }
}
