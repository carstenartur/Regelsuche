package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Modular lift normalization and deterministic subset reconstruction. */
final class ZassenhausCandidateArithmetic {
    private ZassenhausCandidateArithmetic() {
    }

    static List<UnivariatePolynomialView<BigInteger>> monicLiftedFactors(
        SparsePolynomial<BigInteger> source,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        if (!lifting.completed()
                || lifting.factors().isEmpty()
                || lifting.factors().size() > policy.maxAcceptedFactors()) {
            throw new IntegerPolynomialArithmetic.AlgorithmFailure(
                "ZASSENHAUS_INVALID_HENSEL_FACTOR_SET");
        }
        BigInteger modulus = lifting.targetModulus();
        if (modulus.signum() <= 0
                || modulus.bitLength() > policy.maxModulusBitLength()) {
            throw new IntegerPolynomialArithmetic.LimitReached(
                "ZASSENHAUS_MODULUS_BIT_LENGTH_POLICY_EXCEEDED");
        }

        ArrayList<UnivariatePolynomialView<BigInteger>> result =
            new ArrayList<>(lifting.factors().size());
        for (int index = 0; index < lifting.factors().size(); index++) {
            SparsePolynomial<BigInteger> lifted = lifting.factors().get(index);
            if (!source.ring().equals(lifted.ring())
                    || lifted.isConstant()) {
                throw new IntegerPolynomialArithmetic.AlgorithmFailure(
                    "ZASSENHAUS_HENSEL_FACTOR_RING_OR_SHAPE_MISMATCH");
            }
            result.add(monicFactor(
                source,
                lifted,
                index,
                modulus,
                policy,
                work));
        }
        return List.copyOf(result);
    }

    private static UnivariatePolynomialView<BigInteger> monicFactor(
        SparsePolynomial<BigInteger> source,
        SparsePolynomial<BigInteger> lifted,
        int factorIndex,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<BigInteger> view =
            UnivariatePolynomialView.from(lifted);
        BigInteger leading = canonicalResidue(
            view.leadingCoefficient(),
            modulus);
        work.consume("zassenhaus.unanchor.leading-inverses", 1);
        BigInteger inverse;
        try {
            inverse = leading.modInverse(modulus);
        } catch (ArithmeticException exception) {
            throw new IntegerPolynomialArithmetic.AlgorithmFailure(
                "ZASSENHAUS_HENSEL_LEADING_COEFFICIENT_NOT_INVERTIBLE");
        }
        ArrayList<BigInteger> coefficients =
            new ArrayList<>(view.coefficientCount());
        for (BigInteger coefficient : view.coefficients()) {
            BigInteger normalized = modularMultiply(
                coefficient,
                inverse,
                modulus,
                policy,
                work,
                "zassenhaus.unanchor.factor-" + factorIndex);
            BigInteger centered = centeredResidue(normalized, modulus);
            requireCoefficientWithinPolicy(centered, policy);
            coefficients.add(centered);
        }
        UnivariatePolynomialView<BigInteger> monic =
            UnivariatePolynomialView.of(source.ring(), coefficients);
        work.consume("zassenhaus.unanchor.monic-tests", 1);
        if (monic.isConstant()
                || !BigInteger.ONE.equals(monic.leadingCoefficient())) {
            throw new IntegerPolynomialArithmetic.AlgorithmFailure(
                "ZASSENHAUS_MONIC_LIFT_RECONSTRUCTION_FAILED");
        }
        return monic;
    }

    static UnivariatePolynomialView<BigInteger> subsetCandidate(
        List<UnivariatePolynomialView<BigInteger>> modularFactors,
        int[] subset,
        BigInteger leadingDivisor,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        validateSubsetInput(
            modularFactors,
            subset,
            leadingDivisor,
            modulus);
        UnivariatePolynomialView<BigInteger> first =
            modularFactors.getFirst();
        UnivariatePolynomialView<BigInteger> product =
            UnivariatePolynomialView.one(first.ring());
        int previous = -1;
        for (int index : subset) {
            if (index <= previous
                    || index < 0
                    || index >= modularFactors.size()
                    || !first.ring().equals(
                        modularFactors.get(index).ring())) {
                throw new IllegalArgumentException(
                    "Zassenhaus subset is not canonical");
            }
            previous = index;
            product = multiplyModulo(
                product,
                modularFactors.get(index),
                modulus,
                policy,
                work,
                "zassenhaus.subset-product");
        }
        return scaleAndCenter(
            product,
            leadingDivisor,
            modulus,
            policy,
            work);
    }

    private static void validateSubsetInput(
        List<UnivariatePolynomialView<BigInteger>> modularFactors,
        int[] subset,
        BigInteger leadingDivisor,
        BigInteger modulus
    ) {
        if (modularFactors.isEmpty()
                || subset.length == 0
                || leadingDivisor.signum() <= 0
                || modulus.compareTo(BigInteger.ONE) <= 0) {
            throw new IllegalArgumentException(
                "invalid Zassenhaus subset candidate input");
        }
    }

    private static UnivariatePolynomialView<BigInteger> scaleAndCenter(
        UnivariatePolynomialView<BigInteger> product,
        BigInteger leadingDivisor,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        ArrayList<BigInteger> coefficients =
            new ArrayList<>(product.coefficientCount());
        for (BigInteger coefficient : product.coefficients()) {
            BigInteger scaled = modularMultiply(
                coefficient,
                leadingDivisor,
                modulus,
                policy,
                work,
                "zassenhaus.subset-leading-scale");
            BigInteger centered = centeredResidue(scaled, modulus);
            requireCoefficientWithinPolicy(centered, policy);
            coefficients.add(centered);
        }
        UnivariatePolynomialView<BigInteger> candidate =
            UnivariatePolynomialView.of(product.ring(), coefficients);
        work.consume("zassenhaus.subset-leading-tests", 1);
        if (candidate.isZero()
                || !candidate.leadingCoefficient().equals(
                    leadingDivisor)) {
            throw new IntegerPolynomialArithmetic.AlgorithmFailure(
                "ZASSENHAUS_SUBSET_LEADING_COEFFICIENT_MISMATCH");
        }
        return candidate;
    }

    private static UnivariatePolynomialView<BigInteger> multiplyModulo(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (!left.ring().equals(right.ring())) {
            throw new IllegalArgumentException(
                "Zassenhaus modular polynomial ring mismatch");
        }
        if (left.isZero() || right.isZero()) {
            return UnivariatePolynomialView.zero(left.ring());
        }
        ArrayList<BigInteger> coefficients = zeros(
            left.degree() + right.degree() + 1);
        for (int leftExponent = 0;
                leftExponent < left.coefficientCount();
                leftExponent++) {
            multiplyRow(
                left.coefficient(leftExponent),
                leftExponent,
                right,
                coefficients,
                modulus,
                policy,
                work,
                stage);
        }
        return UnivariatePolynomialView.of(left.ring(), coefficients);
    }

    private static void multiplyRow(
        BigInteger leftCoefficient,
        int leftExponent,
        UnivariatePolynomialView<BigInteger> right,
        ArrayList<BigInteger> coefficients,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        for (int rightExponent = 0;
                rightExponent < right.coefficientCount();
                rightExponent++) {
            int exponent = leftExponent + rightExponent;
            BigInteger product = modularMultiply(
                leftCoefficient,
                right.coefficient(rightExponent),
                modulus,
                policy,
                work,
                stage + ".multiply");
            work.consume(stage + ".add", 1);
            BigInteger sum = canonicalResidue(
                coefficients.get(exponent).add(product),
                modulus);
            requireCoefficientWithinPolicy(sum, policy);
            coefficients.set(exponent, sum);
        }
    }

    private static BigInteger modularMultiply(
        BigInteger left,
        BigInteger right,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        BigInteger factor = canonicalResidue(left, modulus);
        BigInteger multiplier = canonicalResidue(right, modulus);
        BigInteger result = BigInteger.ZERO;
        while (multiplier.signum() > 0) {
            work.consume(stage + ".bits", 1);
            if (multiplier.testBit(0)) {
                result = modularAdd(result, factor, modulus);
            }
            multiplier = multiplier.shiftRight(1);
            if (multiplier.signum() > 0) {
                factor = modularAdd(factor, factor, modulus);
            }
        }
        requireCoefficientWithinPolicy(result, policy);
        return result;
    }

    private static BigInteger modularAdd(
        BigInteger left,
        BigInteger right,
        BigInteger modulus
    ) {
        BigInteger checkedLeft = canonicalResidue(left, modulus);
        BigInteger checkedRight = canonicalResidue(right, modulus);
        BigInteger complement = modulus.subtract(checkedRight);
        return checkedLeft.compareTo(complement) >= 0
            ? checkedLeft.subtract(complement)
            : checkedLeft.add(checkedRight);
    }

    private static BigInteger centeredResidue(
        BigInteger value,
        BigInteger modulus
    ) {
        BigInteger residue = canonicalResidue(value, modulus);
        BigInteger complement = modulus.subtract(residue);
        return residue.compareTo(complement) > 0
            ? residue.subtract(modulus)
            : residue;
    }

    private static BigInteger canonicalResidue(
        BigInteger value,
        BigInteger modulus
    ) {
        BigInteger residue = value.remainder(modulus);
        return residue.signum() < 0
            ? residue.add(modulus)
            : residue;
    }

    private static void requireCoefficientWithinPolicy(
        BigInteger value,
        ZassenhausRecombinationPolicy policy
    ) {
        if (value.abs().bitLength()
                > policy.maxIntermediateCoefficientBitLength()) {
            throw new IntegerPolynomialArithmetic.LimitReached(
                "ZASSENHAUS_INTERMEDIATE_COEFFICIENT_BIT_LENGTH_EXCEEDED");
        }
    }

    private static ArrayList<BigInteger> zeros(int size) {
        ArrayList<BigInteger> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(BigInteger.ZERO);
        }
        return result;
    }
}
