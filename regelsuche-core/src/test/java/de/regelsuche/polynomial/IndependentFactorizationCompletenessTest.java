package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class IndependentFactorizationCompletenessTest {
    private static final String ENGINE_ID =
        "test.complete-candidate-engine/v1";
    private static final String ENGINE_RESULT_HASH =
        "sha256:" + "b".repeat(64);
    private static final String PROPOSAL_HASH =
        "sha256:" + "c".repeat(64);
    private static final FactorizationRequest.StructuralLimits LIMITS =
        new FactorizationRequest.StructuralLimits(1, 32, 128, 4_096);

    private final PolynomialRing<BigInteger> integerRing =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final PolynomialRing<ExactRational> rationalRing =
        new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);

    @Test
    void certifiesEveryDistinctFactorOfANonprimitiveIntegerProposal() {
        SparsePolynomial<BigInteger> quadratic = integer(2, 2, 4);
        SparsePolynomial<BigInteger> linear = integer(3, 3);
        SparsePolynomial<BigInteger> source = quadratic.pow(2)
            .multiply(linear);
        FactorizationEngine.Proposal<BigInteger> proposal = proposal(
            BigInteger.ONE,
            List.of(
                new PolynomialFactor<>(quadratic, 2),
                new PolynomialFactor<>(linear, 1)),
            SparsePolynomial.one(integerRing));

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine(
                    BigIntegerDomain.DOMAIN_ID,
                    proposal,
                    FactorizationEngine.BackendClaim
                        .COMPLETE_FACTORIZATION),
                request(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.COMPLETE_FACTORIZATION,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .INDEPENDENTLY_CERTIFIED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());
        IndependentCompletenessTrace trace =
            report.independentCompletenessTrace().orElseThrow();
        assertTrue(trace.certified());
        assertEquals(
            report.candidates().getFirst()
                .verificationCertificateHash(),
            trace.selectedCandidateCertificateHash().orElseThrow());
        assertEquals(1, trace.candidateAttempts().size());
        IndependentCompletenessTrace.CandidateAttempt attempt =
            trace.candidateAttempts().getFirst();
        assertEquals(
            IndependentCompletenessTrace.CandidateOutcome.CERTIFIED,
            attempt.outcome());
        assertTrue(attempt.remainderOne());
        assertEquals(2, attempt.factorAttempts().size());
        assertTrue(attempt.factorAttempts().stream().allMatch(
            factor -> factor.irreducibilityTrace().certified()));
        assertTrue(attempt.factorAttempts().stream().allMatch(
            factor -> factor.irreducibilityTrace().requestHash()
                .equals(trace.requestHash())));
        assertTrue(attempt.factorAttempts().stream().allMatch(
            factor -> factor.irreducibilityTrace().sourceHash()
                .equals(factor.factorHash())));
        assertTrue(attempt.factorAttempts().stream().anyMatch(factor ->
            factor.multiplicity() == 2
                && factor.irreducibilityTrace()
                    .normalizedPrimitiveCoefficients()
                    .equals(List.of(
                        BigInteger.ONE,
                        BigInteger.ONE,
                        BigInteger.TWO))));
        assertTrue(attempt.factorAttempts().stream().anyMatch(factor ->
            factor.irreducibilityTrace().primeAttempts().stream()
                .map(FactorizationVerifier.PrimeAttempt::outcome)
                .toList().equals(List.of(
                    FactorizationVerifier.PrimeAttemptOutcome.DEGREE_LOSS,
                    FactorizationVerifier.PrimeAttemptOutcome
                        .IRREDUCIBLE_WITNESS))));
        assertTrue(report.work().units(
            "independent-completeness.factor-dispatches") > 0);

        FactorizationVerifier.Report<BigInteger> repeated =
            FactorizationVerifier.execute(
                engine(
                    BigIntegerDomain.DOMAIN_ID,
                    proposal,
                    FactorizationEngine.BackendClaim
                        .COMPLETE_FACTORIZATION),
                request(source, 100_000));
        assertEquals(
            trace,
            repeated.independentCompletenessTrace().orElseThrow());
        assertEquals(report.verificationHash(), repeated.verificationHash());
        assertEquals(
            0,
            IndependentCompletenessTrace.class
                .getConstructors()
                .length);
    }

    @Test
    void independentlyNormalizesRationalCandidateFactors() {
        SparsePolynomial<ExactRational> quadratic = rational(
            ExactRational.ONE,
            q(1, 2),
            q(1, 2));
        SparsePolynomial<ExactRational> linear = rational(
            q(-1, 3),
            ExactRational.ONE);
        SparsePolynomial<ExactRational> source =
            quadratic.multiply(linear);

        FactorizationVerifier.Report<ExactRational> report =
            FactorizationVerifier.execute(
                engine(
                    ExactRationalField.DOMAIN_ID,
                    proposal(
                        ExactRational.ONE,
                        List.of(
                            new PolynomialFactor<>(quadratic, 1),
                            new PolynomialFactor<>(linear, 1)),
                        SparsePolynomial.one(rationalRing)),
                    FactorizationEngine.BackendClaim.NONE),
                request(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.COMPLETE_FACTORIZATION,
            report.status());
        IndependentCompletenessTrace trace =
            report.independentCompletenessTrace().orElseThrow();
        assertTrue(trace.candidateAttempts().getFirst()
            .factorAttempts().stream().anyMatch(factor ->
                factor.irreducibilityTrace()
                    .normalizedPrimitiveCoefficients()
                    .equals(List.of(
                        BigInteger.TWO,
                        BigInteger.ONE,
                        BigInteger.ONE))));
        assertTrue(trace.candidateAttempts().getFirst()
            .factorAttempts().stream().anyMatch(factor ->
                factor.irreducibilityTrace().primeAttempts().stream()
                    .map(FactorizationVerifier.PrimeAttempt::outcome)
                    .toList().equals(List.of(
                        FactorizationVerifier.PrimeAttemptOutcome
                            .REDUCIBLE_REDUCTION,
                        FactorizationVerifier.PrimeAttemptOutcome
                            .IRREDUCIBLE_WITNESS))));
    }

    @Test
    void aCompleteBackendClaimCannotCertifyAReducibleFactor() {
        SparsePolynomial<BigInteger> source = integer(-1, 0, 1);
        FactorizationEngine.Proposal<BigInteger> proposal = proposal(
            BigInteger.ONE,
            List.of(new PolynomialFactor<>(source, 1)),
            SparsePolynomial.one(integerRing));

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine(
                    BigIntegerDomain.DOMAIN_ID,
                    proposal,
                    FactorizationEngine.BackendClaim
                        .COMPLETE_FACTORIZATION),
                request(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertTrue(report.candidates().isEmpty());
        IndependentCompletenessTrace trace =
            report.independentCompletenessTrace().orElseThrow();
        assertFalse(trace.certified());
        assertEquals(
            IndependentCompletenessTrace.Outcome
                .FACTOR_CERTIFICATE_INCONCLUSIVE,
            trace.outcome());
        assertEquals(
            FactorizationVerifier.IndependentIrreducibilityOutcome
                .NO_WITNESS_WITHIN_POLICY,
            trace.candidateAttempts().getFirst()
                .factorAttempts().getFirst()
                .irreducibilityTrace().outcome());
    }

    @Test
    void anUnresolvedRemainderCannotBecomeACompleteFactorization() {
        SparsePolynomial<BigInteger> left = integer(-1, 1);
        SparsePolynomial<BigInteger> right = integer(1, 1);
        SparsePolynomial<BigInteger> source = left.multiply(right);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine(
                    BigIntegerDomain.DOMAIN_ID,
                    proposal(
                        BigInteger.ONE,
                        List.of(new PolynomialFactor<>(left, 1)),
                        right),
                    FactorizationEngine.BackendClaim.NONE),
                request(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            report.status());
        IndependentCompletenessTrace trace =
            report.independentCompletenessTrace().orElseThrow();
        assertEquals(
            IndependentCompletenessTrace.Outcome
                .NO_REMAINDER_ONE_CANDIDATE,
            trace.outcome());
        assertEquals(
            IndependentCompletenessTrace.CandidateOutcome
                .REMAINDER_NOT_ONE,
            trace.candidateAttempts().getFirst().outcome());
        assertTrue(trace.candidateAttempts().getFirst()
            .factorAttempts().isEmpty());
    }

    @Test
    void campaignSelectsTheFirstIndependentlyCompleteCandidate() {
        SparsePolynomial<BigInteger> left = integer(-1, 1);
        SparsePolynomial<BigInteger> right = integer(1, 1);
        SparsePolynomial<BigInteger> source = left.multiply(right)
            .scale(BigInteger.TWO);
        FactorizationEngine.Proposal<BigInteger> partial = proposal(
            BigInteger.ONE,
            List.of(new PolynomialFactor<>(left, 1)),
            right.scale(BigInteger.TWO));
        FactorizationEngine.Proposal<BigInteger> complete = proposal(
            BigInteger.TWO,
            List.of(
                new PolynomialFactor<>(left, 1),
                new PolynomialFactor<>(right, 1)),
            SparsePolynomial.one(integerRing));

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine(
                    BigIntegerDomain.DOMAIN_ID,
                    List.of(complete, partial),
                    FactorizationEngine.BackendClaim.NONE),
                request(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.COMPLETE_FACTORIZATION,
            report.status());
        IndependentCompletenessTrace trace =
            report.independentCompletenessTrace().orElseThrow();
        assertEquals(
            List.of(
                IndependentCompletenessTrace.CandidateOutcome
                    .REMAINDER_NOT_ONE,
                IndependentCompletenessTrace.CandidateOutcome
                    .CERTIFIED),
            trace.candidateAttempts().stream()
                .map(attempt -> attempt.outcome())
                .toList());
        assertEquals(1, trace.selectedCandidateIndex().orElseThrow());
        assertEquals(
            trace.candidateAttempts().get(1).candidateCertificateHash(),
            report.candidates().getFirst()
                .verificationCertificateHash());
    }

    @Test
    void completenessUsesOnlyTheRemainingRequestWorkBudget() {
        SparsePolynomial<BigInteger> source = integer(1, 0, 1);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine(
                    BigIntegerDomain.DOMAIN_ID,
                    proposal(
                        BigInteger.ONE,
                        List.of(new PolynomialFactor<>(source, 1)),
                        SparsePolynomial.one(integerRing)),
                    FactorizationEngine.BackendClaim
                        .COMPLETE_FACTORIZATION),
                request(source, 3));

        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            report.status());
        assertEquals(
            IndependentCompletenessTrace.Outcome
                .WORK_BUDGET_EXHAUSTED,
            report.independentCompletenessTrace()
                .orElseThrow().outcome());
        assertTrue(report.work().totalWorkUnits() <= 3);
    }

    @Test
    void preservesExistingVerifiedDecompositionReportMaterial() {
        SparsePolynomial<BigInteger> left = integer(-1, 1);
        SparsePolynomial<BigInteger> right = integer(1, 1);
        SparsePolynomial<BigInteger> source = left.multiply(right);
        FactorizationEngine.Proposal<BigInteger> proposal = proposal(
            BigInteger.ONE,
            List.of(
                new PolynomialFactor<>(left, 1),
                new PolynomialFactor<>(right, 1)),
            SparsePolynomial.one(integerRing));
        FactorizationRequest<BigInteger> request =
            FactorizationRequest.verifiedDecomposition(
                source,
                LIMITS,
                8,
                100_000);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                engine(
                    BigIntegerDomain.DOMAIN_ID,
                    proposal,
                    FactorizationEngine.BackendClaim
                        .COMPLETE_FACTORIZATION),
                request);

        assertEquals(
            FactorizationVerifier.Status.PARTIAL_FACTORIZATION,
            report.status());
        assertTrue(report.independentCompletenessTrace().isEmpty());
        assertEquals(
            "sha256:5e9d4ffbda1a3af4f645354d9d2094ed"
                + "64faf08b34917bd24a97ab86fa2e2991",
            report.verificationHash());
    }

    private SparsePolynomial<BigInteger> integer(int... coefficients) {
        return UnivariatePolynomialView.of(
            integerRing,
            java.util.Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList()).toSparsePolynomial();
    }

    private SparsePolynomial<ExactRational> rational(
        ExactRational... coefficients
    ) {
        return UnivariatePolynomialView.of(
            rationalRing,
            List.of(coefficients)).toSparsePolynomial();
    }

    private static ExactRational q(long numerator, long denominator) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }

    private static <C> FactorizationRequest<C> request(
        SparsePolynomial<C> source,
        long work
    ) {
        return FactorizationRequest.independentComplete(
            source,
            LIMITS,
            8,
            work);
    }

    private static <C> FactorizationEngine.Proposal<C> proposal(
        C unit,
        List<PolynomialFactor<C>> factors,
        SparsePolynomial<C> remainder
    ) {
        return new FactorizationEngine.Proposal<>(
            unit,
            factors,
            remainder,
            PROPOSAL_HASH);
    }

    private static <C> FactorizationEngine<C> engine(
        String domainId,
        FactorizationEngine.Proposal<C> proposal,
        FactorizationEngine.BackendClaim claim
    ) {
        return engine(domainId, List.of(proposal), claim);
    }

    private static <C> FactorizationEngine<C> engine(
        String domainId,
        List<FactorizationEngine.Proposal<C>> proposals,
        FactorizationEngine.BackendClaim claim
    ) {
        return new FactorizationEngine<>() {
            @Override
            public String engineId() {
                return ENGINE_ID;
            }

            @Override
            public String coefficientDomainId() {
                return domainId;
            }

            @Override
            public EngineResult<C> propose(FactorizationRequest<C> request) {
                return new EngineResult<>(
                    ENGINE_ID,
                    Outcome.CANDIDATES,
                    "TEST_ENGINE_COMPLETE_PROPOSAL",
                    PolynomialWorkLedger.empty(),
                    proposals,
                    claim,
                    ENGINE_RESULT_HASH);
            }
        };
    }
}
