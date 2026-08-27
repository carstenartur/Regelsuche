package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.FactorizationRequest;
import de.regelsuche.polynomial.Monomial;
import de.regelsuche.polynomial.PolynomialFactor;
import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Independent result invariants for Hensel lifting. */
final class HenselLiftingEvidence {
    private HenselLiftingEvidence() {
    }

    static void validateCompleted(
        BigInteger targetModulus,
        List<SparsePolynomial<BigInteger>> factors,
        List<HenselLiftStep> steps,
        PolynomialWorkLedger work,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy
    ) {
        String requestHash = AlgorithmEvidence.sha256(
            request.canonicalMaterial());
        if (!selection.completed()
                || !selection.sourceRequestHash().equals(requestHash)
                || !(request.source().ring().coefficientDomain()
                    instanceof BigIntegerDomain)
                || request.source().ring().variableCount() != 1
                || request.source().isConstant()
                || !work.within(request.maxWorkUnits())
                || selection.attempts().size()
                    > request.maxCandidates()
                || !extendsSelectionWork(work, selection.work())) {
            throw new IllegalArgumentException(
                "completed Hensel input contract is invalid");
        }
        int prime = selection.selectedPrime();
        validateTargetBound(prime, policy);
        BigInteger expectedModulus = BigInteger.valueOf(prime)
            .pow(policy.targetExponent());
        List<SparsePolynomial<BigInteger>> expectedModularFactors =
            anchoredModularFactors(selection);
        if (!reduce(
                request.source(),
                selection.modularSource().ring())
                .equals(selection.modularSource())
                || !expectedModulus.equals(targetModulus)
                || targetModulus.bitLength()
                    > policy.maxModulusBitLength()
                || factors.size() != expectedModularFactors.size()
                || steps.size() != policy.targetExponent() - 1) {
            throw new IllegalArgumentException(
                "completed Hensel target is invalid");
        }
        validateFactors(
            factors,
            expectedModularFactors,
            request,
            selection,
            policy);
        SparsePolynomial<BigInteger> product =
            SparsePolynomial.one(request.source().ring());
        for (SparsePolynomial<BigInteger> factor : factors) {
            product = product.multiply(factor);
        }
        if (!congruent(request.source(), product, targetModulus)) {
            throw new IllegalArgumentException(
                "completed Hensel factors do not reconstruct modulo target");
        }
    }

    private static void validateTargetBound(
        int prime,
        HenselLiftingPolicy policy
    ) {
        long minimumTargetBits = Math.addExact(
            Math.multiplyExact(
                (long) BigInteger.valueOf(prime).bitLength() - 1L,
                policy.targetExponent()),
            1L);
        if (minimumTargetBits > policy.maxModulusBitLength()) {
            throw new IllegalArgumentException(
                "completed Hensel modulus exceeds the policy");
        }
    }

    private static void validateFactors(
        List<SparsePolynomial<BigInteger>> factors,
        List<SparsePolynomial<BigInteger>> expectedModularFactors,
        FactorizationRequest<BigInteger> request,
        SuitablePrimeSelectionResult selection,
        HenselLiftingPolicy policy
    ) {
        PolynomialRing<BigInteger> integerRing = request.source().ring();
        for (int index = 0; index < factors.size(); index++) {
            SparsePolynomial<BigInteger> factor = factors.get(index);
            boolean leadingCoefficientValid = index == 0
                ? factor.leadingCoefficient().equals(
                    request.source().leadingCoefficient())
                : BigInteger.ONE.equals(factor.leadingCoefficient());
            if (!integerRing.equals(factor.ring())
                    || factor.isConstant()
                    || factor.maxCoefficientBitLength()
                        > policy.maxIntermediateCoefficientBitLength()
                    || !leadingCoefficientValid
                    || !reduce(
                        factor,
                        selection.modularSource().ring())
                        .equals(expectedModularFactors.get(index))) {
                throw new IllegalArgumentException(
                    "completed Hensel factors are invalid");
            }
        }
    }

    static void validateStepSequence(
        List<HenselLiftStep> steps,
        int prime,
        int factorCount
    ) {
        if (steps.isEmpty()) {
            return;
        }
        if (prime < 2 || factorCount < 1) {
            throw new IllegalArgumentException(
                "Hensel steps require a completed modular selection");
        }
        BigInteger expectedModulus = BigInteger.valueOf(prime);
        int expectedExponent = 1;
        for (HenselLiftStep step : steps) {
            BigInteger nextModulus = expectedModulus.multiply(
                BigInteger.valueOf(prime));
            if (step.fromExponent() != expectedExponent
                    || step.toExponent() != expectedExponent + 1
                    || !step.fromModulus().equals(expectedModulus)
                    || !step.toModulus().equals(nextModulus)
                    || step.correctionPolynomialHashes().size()
                        != factorCount) {
                throw new IllegalArgumentException(
                    "Hensel lift steps are not consecutive");
            }
            expectedExponent++;
            expectedModulus = nextModulus;
        }
    }

    private static boolean extendsSelectionWork(
        PolynomialWorkLedger work,
        PolynomialWorkLedger selectionWork
    ) {
        if (work.totalWorkUnits() < selectionWork.totalWorkUnits()) {
            return false;
        }
        return selectionWork.stages().entrySet().stream().allMatch(entry -> {
            long retainedUnits = work.units(entry.getKey());
            return entry.getKey().startsWith("hensel.")
                ? retainedUnits >= entry.getValue()
                : retainedUnits == entry.getValue();
        });
    }

    private static List<SparsePolynomial<BigInteger>>
            anchoredModularFactors(
        SuitablePrimeSelectionResult selection
    ) {
        FiniteFieldFactorizationResult factorization =
            selection.modularFactorization();
        ArrayList<SparsePolynomial<BigInteger>> result =
            new ArrayList<>(factorization.factors().size());
        for (int index = 0;
                index < factorization.factors().size();
                index++) {
            PolynomialFactor<BigInteger> factor =
                factorization.factors().get(index);
            SparsePolynomial<BigInteger> polynomial = factor.polynomial();
            result.add(index == 0
                ? polynomial.scale(factorization.unit())
                : polynomial);
        }
        return List.copyOf(result);
    }

    private static SparsePolynomial<BigInteger> reduce(
        SparsePolynomial<BigInteger> source,
        PolynomialRing<BigInteger> modularRing
    ) {
        if (!(modularRing.coefficientDomain()
                instanceof PrimeField field)) {
            throw new IllegalArgumentException(
                "Hensel modular ring is not a prime field");
        }
        NavigableMap<Monomial, BigInteger> terms =
            new TreeMap<>(modularRing.monomialComparator());
        for (Map.Entry<Monomial, BigInteger> term
                : source.terms().entrySet()) {
            BigInteger coefficient = field.canonical(term.getValue());
            if (!field.isZero(coefficient)) {
                terms.put(term.getKey(), coefficient);
            }
        }
        return new SparsePolynomial<>(modularRing, terms);
    }

    private static boolean congruent(
        SparsePolynomial<BigInteger> source,
        SparsePolynomial<BigInteger> product,
        BigInteger modulus
    ) {
        if (!source.ring().equals(product.ring())
                || modulus.signum() <= 0) {
            return false;
        }
        UnivariatePolynomialView<BigInteger> left =
            UnivariatePolynomialView.from(source);
        UnivariatePolynomialView<BigInteger> right =
            UnivariatePolynomialView.from(product);
        int count = Math.max(
            left.coefficientCount(),
            right.coefficientCount());
        for (int exponent = 0; exponent < count; exponent++) {
            if (left.coefficient(exponent)
                    .subtract(right.coefficient(exponent))
                    .remainder(modulus)
                    .signum() != 0) {
                return false;
            }
        }
        return true;
    }
}
