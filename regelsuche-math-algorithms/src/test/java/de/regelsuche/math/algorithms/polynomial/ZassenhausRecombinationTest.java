package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZassenhausRecombinationTest {
    private final PolynomialRing<BigInteger> ring =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            32,
            128,
            4_096);
    private final ZassenhausRecombinationPolicy policy =
        ZassenhausRecombinationPolicy.boundedDefaults();

    @Test
    void recombinesModularFactorsIntoTrueIntegerFactors() {
        SparsePolynomial<BigInteger> xMinusOne = polynomial(-1, 1);
        SparsePolynomial<BigInteger> xPlusTwo = polynomial(2, 1);
        SparsePolynomial<BigInteger> xSquaredPlusOne =
            polynomial(1, 0, 1);
        SparsePolynomial<BigInteger> source = xMinusOne
            .multiply(xPlusTwo)
            .multiply(xSquaredPlusOne);
        FactorizationRequest<BigInteger> request = request(source);
        PipelinePrefix prefix = prefix(request, source);

        ZassenhausRecombinationResult first =
            ZassenhausRecombination.recombine(
                request,
                prefix.selection(),
                prefix.lifting(),
                policy);
        ZassenhausRecombinationResult second =
            ZassenhausRecombination.recombine(
                request,
                prefix.selection(),
                prefix.lifting(),
                policy);

        assertTrue(first.completed(), first.toString());
        assertEquals(3, first.factors().size());
        assertEquals(first, second);
        assertEquals(
            List.of(
                xMinusOne.canonicalMaterial(),
                xPlusTwo.canonicalMaterial(),
                xSquaredPlusOne.canonicalMaterial()).stream()
                .sorted()
                .toList(),
            first.factors().stream()
                .map(SparsePolynomial::canonicalMaterial)
                .sorted()
                .toList());
        assertTrue(first.candidateAuditHash().matches(
            "sha256:[0-9a-f]{64}"));
    }

    @Test
    void insufficientLiftPrecisionRemainsInconclusive() {
        SparsePolynomial<BigInteger> source = polynomial(
            -2, -1, 0, 1);
        FactorizationRequest<BigInteger> request = request(source);
        SuitablePrimeSelectionResult selection =
            SuitablePrimeSelection.selectAndFactor(
                request,
                selectionPolicy());
        assertTrue(selection.completed(), selection.toString());
        HenselLiftingResult shortLift = HenselLifting.lift(
            request,
            selection,
            HenselLiftingPolicy.linearMultifactor(
                1,
                4_096,
                65_536));
        assertTrue(shortLift.completed(), shortLift.toString());

        ZassenhausRecombinationResult result =
            ZassenhausRecombination.recombine(
                request,
                selection,
                shortLift,
                policy);

        assertEquals(
            ZassenhausRecombinationResult.Status.BUDGET_INCONCLUSIVE,
            result.status());
        assertEquals(
            "ZASSENHAUS_LIFT_MODULUS_INSUFFICIENT",
            result.detailCode());
        assertTrue(result.factors().isEmpty());
    }

    @Test
    void resultAndPolicyEvidenceAreIssuerOwned() {
        assertEquals(
            0,
            ZassenhausRecombinationResult.class
                .getConstructors().length);
        assertThrows(IllegalArgumentException.class, () ->
            new ZassenhausRecombinationPolicy(
                ZassenhausRecombinationPolicy.Algorithm
                    .DETERMINISTIC_SUBSET_SEARCH_V1,
                0,
                1,
                128,
                8,
                128));
    }

    private PipelinePrefix prefix(
        FactorizationRequest<BigInteger> request,
        SparsePolynomial<BigInteger> source
    ) {
        PolynomialWorkBudget work =
            new PolynomialWorkBudget(request.maxWorkUnits());
        BigInteger bound =
            IntegerPolynomialArithmetic.coefficientBound(
                source,
                policy,
                work);
        SuitablePrimeSelectionResult selection =
            SuitablePrimeSelection.selectAndFactor(
                request,
                selectionPolicy(),
                work);
        assertTrue(selection.completed(), selection.toString());
        int exponent =
            IntegerPolynomialArithmetic.minimumHenselExponent(
                selection.selectedPrime(),
                bound,
                policy);
        HenselLiftingResult lifting = HenselLifting.lift(
            request,
            selection,
            HenselLiftingPolicy.linearMultifactor(
                exponent,
                policy.maxModulusBitLength(),
                policy.maxIntermediateCoefficientBitLength()),
            work);
        assertTrue(lifting.completed(), lifting.toString());
        return new PipelinePrefix(selection, lifting);
    }

    private SuitablePrimeSelectionPolicy selectionPolicy() {
        return SuitablePrimeSelectionPolicy.deterministicAscending(
            101,
            26,
            FiniteFieldFactorizationPolicy.deterministicBerlekamp(
                101,
                1_000_000));
    }

    private FactorizationRequest<BigInteger> request(
        SparsePolynomial<BigInteger> source
    ) {
        return FactorizationRequest.verifiedDecomposition(
            source,
            limits,
            250_000,
            20_000_000);
    }

    private SparsePolynomial<BigInteger> polynomial(
        long... coefficients
    ) {
        return UnivariatePolynomialView.of(
            ring,
            Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }

    private record PipelinePrefix(
        SuitablePrimeSelectionResult selection,
        HenselLiftingResult lifting
    ) {
    }
}
