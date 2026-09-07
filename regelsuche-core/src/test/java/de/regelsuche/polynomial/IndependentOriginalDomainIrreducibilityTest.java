package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndependentOriginalDomainIrreducibilityTest {
    private static final String ENGINE_ID =
        "test.no-candidate-engine/v1";
    private static final String ENGINE_RESULT_HASH =
        "sha256:" + "a".repeat(64);
    private static final FactorizationRequest.StructuralLimits LIMITS =
        new FactorizationRequest.StructuralLimits(1, 16, 32, 4_096);

    private final PolynomialRing<BigInteger> integerRing =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC);
    private final PolynomialRing<ExactRational> rationalRing =
        new PolynomialRing<>(
            ExactRationalField.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.GRADED_LEXICOGRAPHIC);

    @Test
    void certifiesAnIntegerPolynomialAfterRetainingADegreeLosingPrime() {
        SparsePolynomial<BigInteger> source = integer(-2, -2, -4);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.NONE),
                independentRequest(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.IRREDUCIBLE,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .INDEPENDENTLY_CERTIFIED_IRREDUCIBLE,
            report.claimStrength());
        FactorizationVerifier.IndependentIrreducibilityTrace trace =
            report.independentIrreducibilityTrace().orElseThrow();
        assertEquals(
            FactorizationVerifier.IndependentIrreducibilityOutcome
                .CERTIFIED,
            trace.outcome());
        assertEquals(
            FactorizationVerifier.IrreducibilityProofMethod.MODULAR_RABIN,
            trace.proofMethod());
        assertEquals(List.of(
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.TWO),
            trace.normalizedPrimitiveCoefficients());
        assertEquals(3, trace.selectedPrime().orElseThrow());
        assertEquals(
            List.of(
                FactorizationVerifier.PrimeAttemptOutcome.DEGREE_LOSS,
                FactorizationVerifier.PrimeAttemptOutcome
                    .IRREDUCIBLE_WITNESS),
            trace.primeAttempts().stream()
                .map(FactorizationVerifier.PrimeAttempt::outcome)
                .toList());
        assertTrue(trace.traceHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(report.work().units(
            "independent-irreducibility.prime-reduction") > 0);
        FactorizationVerifier.Report<BigInteger> repeated =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.NONE),
                independentRequest(source, 100_000));
        assertEquals(
            trace,
            repeated.independentIrreducibilityTrace().orElseThrow());
        assertEquals(report.verificationHash(), repeated.verificationHash());
        assertEquals(
            0,
            FactorizationVerifier.IndependentIrreducibilityTrace.class
                .getConstructors()
                .length);
    }

    @Test
    void normalizesRationalContentBeforeUsingAModularWitness() {
        SparsePolynomial<ExactRational> source = rational(
            ExactRational.ONE,
            new ExactRational(BigInteger.ONE, BigInteger.TWO),
            new ExactRational(BigInteger.ONE, BigInteger.TWO));

        FactorizationVerifier.Report<ExactRational> report =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    ExactRationalField.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.IRREDUCIBLE),
                independentRequest(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.IRREDUCIBLE,
            report.status());
        FactorizationVerifier.IndependentIrreducibilityTrace trace =
            report.independentIrreducibilityTrace().orElseThrow();
        assertEquals(List.of(
            BigInteger.TWO,
            BigInteger.ONE,
            BigInteger.ONE),
            trace.normalizedPrimitiveCoefficients());
        assertEquals(
            List.of(
                FactorizationVerifier.PrimeAttemptOutcome
                    .REDUCIBLE_REDUCTION,
                FactorizationVerifier.PrimeAttemptOutcome
                    .IRREDUCIBLE_WITNESS),
            trace.primeAttempts().stream()
                .map(FactorizationVerifier.PrimeAttempt::outcome)
                .toList());
        assertEquals(3, trace.selectedPrime().orElseThrow());
    }

    @Test
    void aBoundedEngineMissAndForgedClaimDoNotProveReducibleSource() {
        SparsePolynomial<BigInteger> source = integer(-1, 0, 1);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.IRREDUCIBLE),
                independentRequest(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .BACKEND_CLAIMED_IRREDUCIBLE,
            report.claimStrength());
        FactorizationVerifier.IndependentIrreducibilityTrace trace =
            report.independentIrreducibilityTrace().orElseThrow();
        assertEquals(
            FactorizationVerifier.IndependentIrreducibilityOutcome
                .NO_WITNESS_WITHIN_POLICY,
            trace.outcome());
        assertTrue(trace.selectedPrime().isEmpty());
        assertFalse(trace.primeAttempts().isEmpty());
        assertTrue(trace.primeAttempts().stream().allMatch(attempt ->
            attempt.outcome()
                == FactorizationVerifier.PrimeAttemptOutcome
                    .REDUCIBLE_REDUCTION));
    }

    @Test
    void sufficientModularPolicyDoesNotClaimToDecideEveryIrreducible() {
        SparsePolynomial<BigInteger> source = integer(1, 0, 0, 0, 1);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.NONE),
                independentRequest(source, 100_000));

        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            report.status());
        assertEquals(
            FactorizationVerifier.IndependentIrreducibilityOutcome
                .NO_WITNESS_WITHIN_POLICY,
            report.independentIrreducibilityTrace()
                .orElseThrow()
                .outcome());
        assertEquals(
            FactorizationVerifier.ClaimStrength.NONE,
            report.claimStrength());
    }

    @Test
    void workBudgetExhaustionFailsClosedAndRemainsAccounted() {
        SparsePolynomial<BigInteger> source = integer(2, 1, 1);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.NONE),
                independentRequest(source, 1));

        assertEquals(
            FactorizationVerifier.Status.BUDGET_INCONCLUSIVE,
            report.status());
        assertEquals(
            FactorizationVerifier.IndependentIrreducibilityOutcome
                .WORK_BUDGET_EXHAUSTED,
            report.independentIrreducibilityTrace()
                .orElseThrow()
                .outcome());
        assertTrue(report.work().totalWorkUnits() <= 1);
    }

    @Test
    void fixedDegreeAndNormalizationLimitsFailBeforeLargeArithmetic() {
        SparsePolynomial<BigInteger> highDegree =
            new SparsePolynomial<>(
                integerRing,
                Map.of(
                    Monomial.of(257), BigInteger.ONE,
                    Monomial.of(0), BigInteger.ONE));
        FactorizationRequest.StructuralLimits broadLimits =
            new FactorizationRequest.StructuralLimits(
                1,
                300,
                300,
                20_000);
        FactorizationVerifier.Report<BigInteger> degreeReport =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.NONE),
                FactorizationRequest.independentComplete(
                    highDegree,
                    broadLimits,
                    8,
                    100_000));

        SparsePolynomial<BigInteger> largeCoefficient =
            UnivariatePolynomialView.of(
                integerRing,
                List.of(
                    BigInteger.ONE,
                    BigInteger.ONE.shiftLeft(16_384),
                    BigInteger.ONE))
                .toSparsePolynomial();
        FactorizationVerifier.Report<BigInteger> normalizationReport =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.NONE),
                FactorizationRequest.independentComplete(
                    largeCoefficient,
                    broadLimits,
                    8,
                    100_000));

        assertEquals(
            FactorizationVerifier.IndependentIrreducibilityOutcome
                .DEGREE_LIMIT_EXCEEDED,
            degreeReport.independentIrreducibilityTrace()
                .orElseThrow()
                .outcome());
        assertEquals(
            FactorizationVerifier.IndependentIrreducibilityOutcome
                .NORMALIZATION_LIMIT_EXCEEDED,
            normalizationReport.independentIrreducibilityTrace()
                .orElseThrow()
                .outcome());
    }

    @Test
    void linearEvidenceUsesADirectExactProofWithoutPrimeAttempts() {
        SparsePolynomial<BigInteger> source = integer(7, 5);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                noCandidateEngine(
                    BigIntegerDomain.DOMAIN_ID,
                    FactorizationEngine.BackendClaim.NONE),
                independentRequest(source, 100));

        assertEquals(
            FactorizationVerifier.Status.IRREDUCIBLE,
            report.status());
        FactorizationVerifier.IndependentIrreducibilityTrace trace =
            report.independentIrreducibilityTrace().orElseThrow();
        assertEquals(
            FactorizationVerifier.IrreducibilityProofMethod.LINEAR_DEGREE,
            trace.proofMethod());
        assertTrue(trace.primeAttempts().isEmpty());
        assertTrue(trace.selectedPrime().isEmpty());
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

    private static <C> FactorizationRequest<C> independentRequest(
        SparsePolynomial<C> source,
        long work
    ) {
        return FactorizationRequest.independentComplete(
            source,
            LIMITS,
            8,
            work);
    }

    private static <C> FactorizationEngine<C> noCandidateEngine(
        String domainId,
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
                    Outcome.NO_CANDIDATE,
                    "TEST_ENGINE_FOUND_NO_CANDIDATE",
                    PolynomialWorkLedger.empty(),
                    List.of(),
                    claim,
                    ENGINE_RESULT_HASH);
            }
        };
    }
}
