package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.IndependentCompletenessTrace;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NativeUnivariateFactorizationEngineTest {
    private static final FactorizationRequest.StructuralLimits LIMITS =
        new FactorizationRequest.StructuralLimits(
            1,
            32,
            128,
            4_096);
    private static final int CANDIDATES = 250_000;
    private static final long WORK = 20_000_000;

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
    void verifiesRationalQuadraticsAndUnitRootProposalsWithTightWork() {
        var engine = NativeUnivariateFactorizationEngine.rationalsWithFastPath(
            NativeUnivariateFactorizationPolicy.boundedDefaults());
        var quadratic = new SparsePolynomial<>(rationalRing, Map.of(
            Monomial.of(2), ExactRational.integer(6),
            Monomial.of(1), ExactRational.integer(-7),
            Monomial.of(0), ExactRational.integer(2)));
        var cubic = new SparsePolynomial<>(rationalRing, Map.of(
            Monomial.of(3), ExactRational.integer(3),
            Monomial.of(2), ExactRational.integer(-3),
            Monomial.of(1), ExactRational.integer(2),
            Monomial.of(0), ExactRational.integer(-2)));
        for (var source : List.of(quadratic, cubic)) {
            var request = FactorizationRequest.verifiedDecomposition(source, LIMITS, 1, 64);
            var result = FactorizationVerifier.execute(engine, request);
            assertTrue(result.successful(), result.detailCode());
            assertEquals(FactorizationVerifier.ClaimStrength.VERIFIED_DECOMPOSITION, result.claimStrength());
            assertTrue(result.work().within(64));
        }
    }

    @Test
    void verifiesQuadraticInAPowerWithoutDensifyingThePolynomial() {
        var engine = NativeUnivariateFactorizationEngine.rationalsWithFastPath(
            NativeUnivariateFactorizationPolicy.boundedDefaults());
        var source = new SparsePolynomial<>(rationalRing, Map.of(
            Monomial.of(12), ExactRational.integer(6),
            Monomial.of(6), ExactRational.integer(-7),
            Monomial.of(0), ExactRational.integer(2)));
        var result = FactorizationVerifier.execute(engine, FactorizationRequest.verifiedDecomposition(source, LIMITS, 1, 64));
        assertTrue(result.successful(), result.detailCode());
        assertEquals(FactorizationVerifier.ClaimStrength.VERIFIED_DECOMPOSITION, result.claimStrength());
        var factors = result.candidates().getFirst().factors();
        assertEquals(2, factors.size());
        assertTrue(factors.stream().allMatch(factor -> factor.polynomial().degree(0) == 6));
        assertTrue(result.work().within(64));
        assertEquals(3, result.work().units("native-rational-fast.quadratic-shape-terms"));
        assertEquals(0, result.work().units("native-rational-fast.synthetic-division"));
    }

    @Test
    void refinesNestedFactorsWithMultiplicitiesAndPreservesTheScalar() {
        var engine = NativeUnivariateFactorizationEngine.rationalsWithRefinement(
            NativeUnivariateFactorizationPolicy.boundedDefaults());
        var source = new SparsePolynomial<>(rationalRing, Map.of(
            Monomial.of(8), ExactRational.integer(6),
            Monomial.of(4), ExactRational.integer(-12),
            Monomial.of(0), ExactRational.integer(6)));
        var result = FactorizationVerifier.execute(engine, FactorizationRequest.verifiedDecomposition(source, LIMITS, 1, WORK));
        assertTrue(result.successful(), result.detailCode());
        assertEquals(FactorizationVerifier.ClaimStrength.VERIFIED_DECOMPOSITION, result.claimStrength());
        var candidate = result.candidates().getFirst();
        assertEquals(ExactRational.integer(6), candidate.unit());
        assertEquals(List.of(1, 1, 2),
            candidate.factors().stream().map(factor -> factor.polynomial().degree(0)).sorted().toList());
        assertTrue(candidate.factors().stream().allMatch(factor -> factor.multiplicity() == 2));
        assertTrue(result.work().units("native-rational-refinement.factor-visits") >= 2);
        assertTrue(result.work().units("verify.product-comparisons") >= 3);
    }

    @Test
    void aTightSharedBudgetKeepsThePreviouslyAvailableExactSplit() {
        var engine = NativeUnivariateFactorizationEngine.rationalsWithRefinement(
            NativeUnivariateFactorizationPolicy.boundedDefaults());
        var source = new SparsePolynomial<>(rationalRing, Map.of(
            Monomial.of(8), ExactRational.ONE, Monomial.of(0), ExactRational.NEGATIVE_ONE));
        var request = FactorizationRequest.verifiedDecomposition(source, LIMITS, 1, 30);
        var result = FactorizationVerifier.execute(engine, request);
        assertTrue(result.successful(), result.detailCode());
        assertTrue(result.work().within(30));
        assertEquals(List.of(4, 4), result.candidates().getFirst().factors().stream()
            .map(factor -> factor.polynomial().degree(0)).sorted().toList());
        assertEquals(FactorizationVerifier.ClaimStrength.VERIFIED_DECOMPOSITION, result.claimStrength());
    }

    @Test
    void completelyFactorsGeneralIntegerPolynomial() {
        SparsePolynomial<BigInteger> xMinusOne = integer(-1, 1);
        SparsePolynomial<BigInteger> xPlusTwo = integer(2, 1);
        SparsePolynomial<BigInteger> xSquaredPlusOne =
            integer(1, 0, 1);
        SparsePolynomial<BigInteger> source = xMinusOne
            .multiply(xPlusTwo)
            .multiply(xSquaredPlusOne)
            .scale(BigInteger.valueOf(6));

        NativeUnivariateFactorizationEngine<BigInteger> engine =
            NativeUnivariateFactorizationEngine.boundedIntegers();
        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(engine, request(source));

        assertTrue(report.successful(), report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());
        assertEquals(
            BigInteger.valueOf(6),
            report.candidates().getFirst().unit());
        assertEquals(
            List.of(xMinusOne, xPlusTwo, xSquaredPlusOne).stream()
                .map(SparsePolynomial::canonicalMaterial)
                .sorted()
                .toList(),
            report.candidates().getFirst().factors().stream()
                .map(PolynomialFactor::polynomial)
                .map(SparsePolynomial::canonicalMaterial)
                .sorted()
                .toList());
    }

    @Test
    void preservesRationalUnitAndRepeatedMultiplicities() {
        SparsePolynomial<ExactRational> xMinusHalf =
            rational(q(-1, 2), ExactRational.ONE);
        SparsePolynomial<ExactRational> xPlusThird =
            rational(q(1, 3), ExactRational.ONE);
        SparsePolynomial<ExactRational> source = xMinusHalf
            .pow(3)
            .multiply(xPlusThird.pow(2))
            .scale(q(-7, 11));

        NativeUnivariateFactorizationEngine<ExactRational> engine =
            NativeUnivariateFactorizationEngine.boundedRationals();
        FactorizationVerifier.Report<ExactRational> report =
            FactorizationVerifier.execute(engine, request(source));

        assertTrue(report.successful(), report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());
        List<Integer> multiplicities =
            report.candidates().getFirst().factors().stream()
                .map(PolynomialFactor::multiplicity)
                .sorted()
                .toList();
        assertEquals(List.of(2, 3), multiplicities);
    }

    @Test
    void factorsRepeatedNonlinearIntegerLayersUnderOneWorkAuthority() {
        SparsePolynomial<BigInteger> xMinusOne = integer(-1, 1);
        SparsePolynomial<BigInteger> xPlusTwo = integer(2, 1);
        SparsePolynomial<BigInteger> xSquaredPlusOne =
            integer(1, 0, 1);
        SparsePolynomial<BigInteger> source = xMinusOne.pow(2)
            .multiply(xPlusTwo.pow(2))
            .multiply(xSquaredPlusOne);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                NativeUnivariateFactorizationEngine.boundedIntegers(),
                request(source));

        assertTrue(report.successful(), report.toString());
        assertEquals(
            FactorizationVerifier.ClaimStrength.BACKEND_CLAIMED_COMPLETE,
            report.claimStrength());
        assertEquals(1, report.candidates().size());
        assertEquals(
            List.of(1, 2, 2),
            report.candidates().getFirst().factors().stream()
                .map(PolynomialFactor::multiplicity)
                .sorted()
                .toList());
        assertEquals(
            List.of(xMinusOne, xPlusTwo, xSquaredPlusOne).stream()
                .map(SparsePolynomial::canonicalMaterial)
                .sorted()
                .toList(),
            report.candidates().getFirst().factors().stream()
                .map(PolynomialFactor::polynomial)
                .map(SparsePolynomial::canonicalMaterial)
                .sorted()
                .toList());
    }

    @Test
    void exhaustivelyRetainsIrreducibilityOnlyAsBackendClaim() {
        SparsePolynomial<BigInteger> source = integer(1, 0, 1);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                NativeUnivariateFactorizationEngine.boundedIntegers(),
                request(source));

        assertEquals(
            FactorizationVerifier.Status.NO_FACTORIZATION_FOUND,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .BACKEND_CLAIMED_IRREDUCIBLE,
            report.claimStrength());
        assertTrue(report.candidates().isEmpty());
    }

    @Test
    void independentlyCertifiesOriginalDomainIrreducibilityOnRequest() {
        SparsePolynomial<BigInteger> source = integer(1, 0, 1);
        FactorizationRequest<BigInteger> independent =
            FactorizationRequest.independentComplete(
                source,
                LIMITS,
                CANDIDATES,
                WORK);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                NativeUnivariateFactorizationEngine.boundedIntegers(),
                independent);

        assertEquals(
            FactorizationVerifier.Status.IRREDUCIBLE,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .INDEPENDENTLY_CERTIFIED_IRREDUCIBLE,
            report.claimStrength());
        FactorizationVerifier.IndependentIrreducibilityTrace trace =
            report.independentIrreducibilityTrace().orElseThrow();
        assertEquals(3, trace.selectedPrime().orElseThrow());
        assertEquals(
            List.of(
                FactorizationVerifier.PrimeAttemptOutcome
                    .REDUCIBLE_REDUCTION,
                FactorizationVerifier.PrimeAttemptOutcome
                    .IRREDUCIBLE_WITNESS),
            trace.primeAttempts().stream()
                .map(FactorizationVerifier.PrimeAttempt::outcome)
                .toList());
        assertTrue(report.work().totalWorkUnits() <= WORK);
    }

    @Test
    void independentlyCertifiesACompleteNativeFactorizationOnRequest() {
        SparsePolynomial<BigInteger> xMinusOne = integer(-1, 1);
        SparsePolynomial<BigInteger> xSquaredPlusOne =
            integer(1, 0, 1);
        SparsePolynomial<BigInteger> source = xMinusOne.pow(2)
            .multiply(xSquaredPlusOne)
            .scale(BigInteger.valueOf(6));
        FactorizationRequest<BigInteger> independent =
            FactorizationRequest.independentComplete(
                source,
                LIMITS,
                CANDIDATES,
                WORK);

        FactorizationVerifier.Report<BigInteger> report =
            FactorizationVerifier.execute(
                NativeUnivariateFactorizationEngine.boundedIntegers(),
                independent);

        assertEquals(
            FactorizationVerifier.Status.COMPLETE_FACTORIZATION,
            report.status());
        assertEquals(
            FactorizationVerifier.ClaimStrength
                .INDEPENDENTLY_CERTIFIED_COMPLETE,
            report.claimStrength());
        IndependentCompletenessTrace trace =
            report.independentCompletenessTrace().orElseThrow();
        assertTrue(trace.certified());
        assertEquals(1, trace.candidateAttempts().size());
        assertEquals(
            2,
            trace.candidateAttempts().getFirst()
                .factorAttempts().size());
        assertTrue(trace.candidateAttempts().getFirst()
            .factorAttempts().stream().allMatch(attempt ->
                attempt.irreducibilityTrace().certified()));
        assertTrue(report.work().totalWorkUnits() <= WORK);
    }

    @Test
    void candidateExhaustionIsNotIrreducibility() {
        SparsePolynomial<BigInteger> source = integer(-2, 1, 1);
        FactorizationRequest<BigInteger> bounded =
            FactorizationRequest.verifiedDecomposition(
                source,
                LIMITS,
                1,
                WORK);

        FactorizationEngine.EngineResult<BigInteger> result =
            NativeUnivariateFactorizationEngine.boundedIntegers()
                .propose(bounded);

        assertEquals(
            FactorizationEngine.Outcome.BUDGET_INCONCLUSIVE,
            result.outcome());
        assertEquals(
            FactorizationEngine.BackendClaim.NONE,
            result.backendClaim());
        assertTrue(result.proposals().isEmpty());
    }

    @Test
    void multivariateInputRemainsUnsupported() {
        PolynomialRing<BigInteger> multivariate =
            new PolynomialRing<>(
                BigIntegerDomain.INSTANCE,
                List.of(
                    new PolynomialVariable("x"),
                    new PolynomialVariable("y")),
                PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
        SparsePolynomial<BigInteger> source =
            new SparsePolynomial<>(
                multivariate,
                java.util.Map.of(
                    de.regelsuche.polynomial.Monomial.of(1, 0),
                    BigInteger.ONE,
                    de.regelsuche.polynomial.Monomial.of(0, 1),
                    BigInteger.ONE));
        FactorizationRequest<BigInteger> request =
            FactorizationRequest.verifiedDecomposition(
                source,
                new FactorizationRequest.StructuralLimits(
                    2,
                    8,
                    16,
                    128),
                10,
                10_000);

        FactorizationEngine.EngineResult<BigInteger> result =
            NativeUnivariateFactorizationEngine.boundedIntegers()
                .propose(request);

        assertEquals(
            FactorizationEngine.Outcome.UNSUPPORTED_REQUEST,
            result.outcome());
        assertFalse(result.backendClaim()
            == FactorizationEngine.BackendClaim.IRREDUCIBLE);
    }

    @Test
    void runningTotalRetainsEveryStageAndBudgetBoundary() {
        NativeFactorizationContractChecks.runningTotalRetainsEveryStageAndBudgetBoundary();
    }

    @Test
    void rationalConstantsHaveVerifiedEmptyFactorizations() {
        NativeFactorizationContractChecks.rationalConstantsHaveVerifiedEmptyFactorizations();
    }

    @Test
    void integerConstantsRetainContentRatherThanClaimIntegerPrimality() {
        NativeFactorizationContractChecks.integerConstantsRetainContentRatherThanClaimIntegerPrimality();
    }

    @Test
    void unitOnlyProposalsRemainFailClosed() {
        NativeFactorizationContractChecks.unitOnlyProposalsRemainFailClosed();
    }

    @Test
    void constantBudgetsCannotBeBypassed() {
        NativeFactorizationContractChecks.constantBudgetsCannotBeBypassed();
    }

    @Test
    void explicitDegreeBudgetEnablesTheExistingAlgorithm() {
        NativeFactorizationContractChecks.explicitDegreeBudgetEnablesTheExistingAlgorithm();
    }

    private SparsePolynomial<BigInteger> integer(
        long... coefficients
    ) {
        return UnivariatePolynomialView.of(
            integerRing,
            Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }

    private SparsePolynomial<ExactRational> rational(
        ExactRational... coefficients
    ) {
        return UnivariatePolynomialView.of(
            rationalRing,
            List.of(coefficients))
            .toSparsePolynomial();
    }

    private static ExactRational q(
        long numerator,
        long denominator
    ) {
        return new ExactRational(
            BigInteger.valueOf(numerator),
            BigInteger.valueOf(denominator));
    }

    private static <C> FactorizationRequest<C> request(
        SparsePolynomial<C> source
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            LIMITS,
            CANDIDATES,
            WORK);
    }
}
