package de.regelsuche.math.algorithms.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialVariable;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HenselLiftingEvidenceTest {
    private final PolynomialRing<BigInteger> integerRing =
        new PolynomialRing<>(
            BigIntegerDomain.INSTANCE,
            List.of(new PolynomialVariable("x")),
            PolynomialRing.MonomialOrder.LEXICOGRAPHIC);
    private final FactorizationRequest.StructuralLimits limits =
        new FactorizationRequest.StructuralLimits(
            1,
            16,
            32,
            1_024);
    private final FiniteFieldFactorizationPolicy finiteFieldPolicy =
        FiniteFieldFactorizationPolicy.deterministicBerlekamp(
            101,
            1_000_000);

    @Test
    void certificatesAreDeterministicAndBindEveryLiftStep() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionResult selection = selection(request);
        HenselLiftingPolicy policy = policy();

        HenselLiftingResult first = HenselLifting.lift(
            request,
            selection,
            policy);
        HenselLiftingResult second = HenselLifting.lift(
            request,
            selection,
            policy);

        assertTrue(first.completed(), first.toString());
        assertEquals(first, second);
        assertEquals(
            selection.certificateHash(),
            first.selectionCertificateHash());
        assertTrue(first.certificateHash().matches(
            "sha256:[0-9a-f]{64}"));
        assertTrue(first.steps().stream().allMatch(step ->
            step.certificateHash().matches("sha256:[0-9a-f]{64}")
                && step.errorPolynomialHash().matches(
                    "sha256:[0-9a-f]{64}")
                && step.liftedProductHash().matches(
                    "sha256:[0-9a-f]{64}")
                && step.correctionPolynomialHashes().stream()
                    .allMatch(hash -> hash.matches(
                        "sha256:[0-9a-f]{64}"))));
    }

    @Test
    void issuerRejectsFactorsThatDoNotRetainTheSelectedReduction() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionResult selection = selection(request);
        HenselLiftingPolicy policy = policy();
        HenselLiftingResult valid = HenselLifting.lift(
            request,
            selection,
            policy);
        assertTrue(valid.completed(), valid.toString());

        ArrayList<SparsePolynomial<BigInteger>> forged =
            new ArrayList<>(valid.factors());
        UnivariatePolynomialView<BigInteger> first =
            UnivariatePolynomialView.from(forged.getFirst());
        ArrayList<BigInteger> coefficients =
            new ArrayList<>(first.coefficients());
        coefficients.set(
            0,
            coefficients.getFirst().add(BigInteger.ONE));
        forged.set(
            0,
            UnivariatePolynomialView.of(
                integerRing,
                coefficients)
                .toSparsePolynomial());

        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingResult.completed(
                valid.targetModulus(),
                forged,
                valid.steps(),
                valid.work(),
                request,
                selection,
                policy));
    }

    @Test
    void issuerRejectsASelectionThatDoesNotReduceTheBoundSource() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionResult selection = selection(request);
        HenselLiftingPolicy policy = policy();
        HenselLiftingResult valid = HenselLifting.lift(
            request,
            selection,
            policy);
        assertTrue(valid.completed(), valid.toString());

        FactorizationRequest<BigInteger> anotherRequest =
            FactorizationRequest.verifiedDecomposition(
                integer(10, 2, 2),
                limits,
                16,
                1_000_000);

        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingResult.completed(
                valid.targetModulus(),
                valid.factors(),
                valid.steps(),
                valid.work(),
                anotherRequest,
                selection,
                policy));
    }

    @Test
    void issuerRejectsAnotherExactRequestWithTheSameModuloPSource() {
        FactorizationRequest<BigInteger> firstRequest = request();
        SuitablePrimeSelectionResult firstSelection =
            selection(firstRequest);
        FactorizationRequest<BigInteger> secondRequest =
            FactorizationRequest.verifiedDecomposition(
                integer(15, 2, 2),
                limits,
                16,
                1_000_000);
        SuitablePrimeSelectionResult secondSelection =
            selection(secondRequest);
        HenselLiftingPolicy policy = policy();
        HenselLiftingResult secondLift = HenselLifting.lift(
            secondRequest,
            secondSelection,
            policy);

        assertTrue(secondLift.completed(), secondLift.toString());
        assertEquals(
            firstSelection.modularSource(),
            secondSelection.modularSource(),
            "the adversarial requests must be indistinguishable modulo 5");
        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingResult.completed(
                secondLift.targetModulus(),
                secondLift.factors(),
                secondLift.steps(),
                secondLift.work(),
                secondRequest,
                firstSelection,
                policy));
    }

    @Test
    void issuerRejectsAChangedTargetOrIncompleteStepSequence() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionResult selection = selection(request);
        HenselLiftingPolicy policy = policy();
        HenselLiftingResult valid = HenselLifting.lift(
            request,
            selection,
            policy);
        assertTrue(valid.completed(), valid.toString());

        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingResult.completed(
                valid.targetModulus().multiply(BigInteger.valueOf(5)),
                valid.factors(),
                valid.steps(),
                valid.work(),
                request,
                selection,
                policy));
        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingResult.completed(
                valid.targetModulus(),
                valid.factors(),
                valid.steps().subList(0, 1),
                valid.work(),
                request,
                selection,
                policy));
    }

    @Test
    void issuerRejectsWorkThatDoesNotExtendTheSelectionLedger() {
        FactorizationRequest<BigInteger> request = request();
        SuitablePrimeSelectionResult selection = selection(request);
        HenselLiftingPolicy policy = policy();
        HenselLiftingResult valid = HenselLifting.lift(
            request,
            selection,
            policy);
        assertTrue(valid.completed(), valid.toString());

        Map<String, Long> forgedStages = new LinkedHashMap<>(
            valid.work().stages());
        String selectionStage = selection.work().stages()
            .keySet().iterator().next();
        forgedStages.put(
            selectionStage,
            selection.work().units(selectionStage) + 1);
        PolynomialWorkLedger forgedWork =
            new PolynomialWorkLedger(forgedStages);

        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftingResult.completed(
                valid.targetModulus(),
                valid.factors(),
                valid.steps(),
                forgedWork,
                request,
                selection,
                policy));
    }

    @Test
    void stepIssuerRejectsMalformedHashesAndTransitions() {
        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftStep.issue(
                1,
                3,
                BigInteger.valueOf(5),
                BigInteger.valueOf(125),
                "sha256:" + "0".repeat(64),
                List.of("sha256:" + "1".repeat(64)),
                "sha256:" + "2".repeat(64),
                1));
        assertThrows(IllegalArgumentException.class, () ->
            HenselLiftStep.issue(
                1,
                2,
                BigInteger.valueOf(5),
                BigInteger.valueOf(25),
                "not-a-hash",
                List.of("sha256:" + "1".repeat(64)),
                "sha256:" + "2".repeat(64),
                1));
    }

    private FactorizationRequest<BigInteger> request() {
        return FactorizationRequest.verifiedDecomposition(
            integer(5, 2, 2),
            limits,
            16,
            1_000_000);
    }

    private SuitablePrimeSelectionResult selection(
        FactorizationRequest<BigInteger> request
    ) {
        SuitablePrimeSelectionPolicy policy =
            new SuitablePrimeSelectionPolicy(
                SuitablePrimeSelectionPolicy.Algorithm
                    .DETERMINISTIC_ASCENDING_PRIMES_V1,
                List.of(5),
                finiteFieldPolicy);
        SuitablePrimeSelectionResult result =
            SuitablePrimeSelection.selectAndFactor(request, policy);
        assertTrue(result.completed(), result.toString());
        return result;
    }

    private static HenselLiftingPolicy policy() {
        return HenselLiftingPolicy.linearMultifactor(
            3,
            1_024,
            4_096);
    }

    private SparsePolynomial<BigInteger> integer(long... coefficients) {
        return UnivariatePolynomialView.of(
            integerRing,
            Arrays.stream(coefficients)
                .mapToObj(BigInteger::valueOf)
                .toList())
            .toSparsePolynomial();
    }
}
