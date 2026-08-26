package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.PrimeField;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Integer, modulus and representation arithmetic for Hensel lifting. */
final class HenselIntegerArithmetic {
    private HenselIntegerArithmetic() {
    }

    static BigInteger targetModulus(
        BigInteger prime,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work
    ) {
        BigInteger result = BigInteger.ONE;
        for (int exponent = 0;
                exponent < policy.targetExponent();
                exponent++) {
            result = multiplyModulus(
                result,
                prime,
                policy,
                work,
                "hensel.target-modulus");
        }
        return result;
    }

    static BigInteger nextModulus(
        BigInteger currentModulus,
        BigInteger prime,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        return multiplyModulus(
            currentModulus,
            prime,
            policy,
            work,
            stage);
    }

    private static BigInteger multiplyModulus(
        BigInteger left,
        BigInteger right,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        work.consume(stage + ".multiplications", 1);
        int guaranteedBits = left.bitLength() + right.bitLength() - 1;
        if (guaranteedBits > policy.maxModulusBitLength()) {
            throw new HenselLifting.RepresentationLimitReached(
                "HENSEL_MODULUS_BIT_LENGTH_POLICY_EXCEEDED");
        }
        BigInteger result = left.multiply(right);
        work.consume(stage + ".bit-length-checks", 1);
        if (result.bitLength() > policy.maxModulusBitLength()) {
            throw new HenselLifting.RepresentationLimitReached(
                "HENSEL_MODULUS_BIT_LENGTH_POLICY_EXCEEDED");
        }
        return result;
    }

    static List<UnivariatePolynomialView<BigInteger>>
            initialIntegerFactors(
        UnivariatePolynomialView<BigInteger> source,
        List<UnivariatePolynomialView<BigInteger>> modularFactors,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work
    ) {
        ArrayList<UnivariatePolynomialView<BigInteger>> result =
            new ArrayList<>(modularFactors.size());
        for (int index = 0;
                index < modularFactors.size();
                index++) {
            UnivariatePolynomialView<BigInteger> modular =
                modularFactors.get(index);
            ArrayList<BigInteger> coefficients =
                new ArrayList<>(modular.coefficients());
            work.consume(
                "hensel.initial-lift.coefficients",
                coefficients.size());
            if (index == 0) {
                coefficients.set(
                    coefficients.size() - 1,
                    source.leadingCoefficient());
            }
            UnivariatePolynomialView<BigInteger> lifted =
                UnivariatePolynomialView.of(
                    source.ring(),
                    coefficients);
            checkView(
                lifted,
                policy,
                work,
                "hensel.initial-lift.bounds");
            result.add(lifted);
        }
        return List.copyOf(result);
    }

    static UnivariatePolynomialView<BigInteger> errorPolynomial(
        UnivariatePolynomialView<BigInteger> source,
        UnivariatePolynomialView<BigInteger> product,
        BigInteger modulus,
        PrimeField field,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        UnivariatePolynomialView<BigInteger> difference =
            subtractInteger(
                source,
                product,
                policy,
                work,
                stage + ".difference");
        ArrayList<BigInteger> coefficients =
            new ArrayList<>(difference.coefficientCount());
        for (BigInteger coefficient : difference.coefficients()) {
            work.consume(stage + ".exact-divisions", 1);
            BigInteger[] division = coefficient.divideAndRemainder(
                modulus);
            if (division[1].signum() != 0) {
                throw new HenselLifting.AlgorithmFailure(
                    "HENSEL_ERROR_NOT_DIVISIBLE_BY_CURRENT_MODULUS");
            }
            checkCoefficient(division[0], policy);
            work.consume(stage + ".field-reductions", 1);
            coefficients.add(field.canonical(division[0]));
        }
        PolynomialRing<BigInteger> modularRing = new PolynomialRing<>(
            field,
            source.ring().variables(),
            source.ring().monomialOrder());
        UnivariatePolynomialView<BigInteger> error =
            UnivariatePolynomialView.of(
                modularRing,
                coefficients);
        work.consume(stage + ".degree-tests", 1);
        if (!error.isZero() && error.degree() >= source.degree()) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_ERROR_DEGREE_NOT_REDUCED");
        }
        return error;
    }

    static UnivariatePolynomialView<BigInteger> applyCorrection(
        UnivariatePolynomialView<BigInteger> factor,
        UnivariatePolynomialView<BigInteger> correction,
        BigInteger currentModulus,
        BigInteger nextModulus,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (!correction.isZero()
                && correction.degree() >= factor.degree()) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_CORRECTION_DEGREE_EXCEEDED_FACTOR");
        }
        ArrayList<BigInteger> coefficients =
            new ArrayList<>(factor.coefficients());
        for (int exponent = 0;
                exponent < factor.degree();
                exponent++) {
            BigInteger scaled = multiplyCoefficient(
                currentModulus,
                correction.coefficient(exponent),
                policy,
                work,
                stage + ".scale");
            BigInteger updated = addCoefficient(
                factor.coefficient(exponent),
                scaled,
                policy,
                work,
                stage + ".add");
            work.consume(stage + ".centered-residues", 1);
            BigInteger centered = centeredResidue(
                updated,
                nextModulus);
            checkCoefficient(centered, policy);
            coefficients.set(exponent, centered);
        }
        UnivariatePolynomialView<BigInteger> result =
            UnivariatePolynomialView.of(factor.ring(), coefficients);
        work.consume(stage + ".leading-coefficient-tests", 1);
        if (!result.leadingCoefficient().equals(
                factor.leadingCoefficient())) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_LEADING_COEFFICIENT_CHANGED");
        }
        checkView(result, policy, work, stage + ".bounds");
        return result;
    }

    private static BigInteger centeredResidue(
        BigInteger value,
        BigInteger modulus
    ) {
        BigInteger residue = value.remainder(modulus);
        if (residue.signum() < 0) {
            residue = residue.add(modulus);
        }
        return residue.shiftLeft(1).compareTo(modulus) > 0
            ? residue.subtract(modulus)
            : residue;
    }

    static void verifyFixedReductions(
        List<UnivariatePolynomialView<BigInteger>> lifted,
        List<UnivariatePolynomialView<BigInteger>> expected,
        PrimeField field,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (lifted.size() != expected.size()) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_FACTOR_COUNT_CHANGED");
        }
        for (int index = 0; index < lifted.size(); index++) {
            UnivariatePolynomialView<BigInteger> reduced =
                HenselModularArithmetic.reduce(
                    lifted.get(index),
                    expected.get(index).ring(),
                    field,
                    work,
                    stage + ".factor-" + index);
            work.consume(stage + ".comparisons", 1);
            if (!reduced.equals(expected.get(index))) {
                throw new HenselLifting.AlgorithmFailure(
                    "HENSEL_FACTOR_REDUCTION_CHANGED");
            }
        }
    }

    static UnivariatePolynomialView<BigInteger> multiplyIntegerFactors(
        List<UnivariatePolynomialView<BigInteger>> factors,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (factors.isEmpty()) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_REQUIRES_AT_LEAST_ONE_FACTOR");
        }
        UnivariatePolynomialView<BigInteger> product =
            UnivariatePolynomialView.one(factors.getFirst().ring());
        for (int index = 0; index < factors.size(); index++) {
            product = multiplyInteger(
                product,
                factors.get(index),
                policy,
                work,
                stage + ".factor-" + index);
        }
        return product;
    }

    private static UnivariatePolynomialView<BigInteger> multiplyInteger(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (!left.ring().equals(right.ring())) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_INTEGER_RING_MISMATCH");
        }
        if (left.isZero() || right.isZero()) {
            return UnivariatePolynomialView.zero(left.ring());
        }
        ArrayList<BigInteger> result = zeros(
            left.degree() + right.degree() + 1);
        for (int leftExponent = 0;
                leftExponent < left.coefficientCount();
                leftExponent++) {
            for (int rightExponent = 0;
                    rightExponent < right.coefficientCount();
                    rightExponent++) {
                BigInteger product = multiplyCoefficient(
                    left.coefficient(leftExponent),
                    right.coefficient(rightExponent),
                    policy,
                    work,
                    stage + ".products");
                int exponent = leftExponent + rightExponent;
                result.set(
                    exponent,
                    addCoefficient(
                        result.get(exponent),
                        product,
                        policy,
                        work,
                        stage + ".sums"));
            }
        }
        return UnivariatePolynomialView.of(left.ring(), result);
    }

    private static UnivariatePolynomialView<BigInteger> subtractInteger(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (!left.ring().equals(right.ring())) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_INTEGER_RING_MISMATCH");
        }
        int count = Math.max(
            left.coefficientCount(),
            right.coefficientCount());
        ArrayList<BigInteger> result = zeros(count);
        for (int exponent = 0; exponent < count; exponent++) {
            work.consume(stage + ".subtractions", 1);
            BigInteger value = left.coefficient(exponent).subtract(
                right.coefficient(exponent));
            checkCoefficient(value, policy);
            result.set(exponent, value);
        }
        return UnivariatePolynomialView.of(left.ring(), result);
    }

    private static BigInteger multiplyCoefficient(
        BigInteger left,
        BigInteger right,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        work.consume(stage, 1);
        if (left.signum() == 0 || right.signum() == 0) {
            return BigInteger.ZERO;
        }
        int guaranteedBits = left.abs().bitLength()
            + right.abs().bitLength() - 1;
        if (guaranteedBits
                > policy.maxIntermediateCoefficientBitLength()) {
            throw new HenselLifting.RepresentationLimitReached(
                "HENSEL_INTERMEDIATE_COEFFICIENT_BIT_LENGTH_EXCEEDED");
        }
        BigInteger result = left.multiply(right);
        checkCoefficient(result, policy);
        return result;
    }

    private static BigInteger addCoefficient(
        BigInteger left,
        BigInteger right,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        work.consume(stage, 1);
        BigInteger result = left.add(right);
        checkCoefficient(result, policy);
        return result;
    }

    static void verifyCongruence(
        UnivariatePolynomialView<BigInteger> source,
        UnivariatePolynomialView<BigInteger> product,
        BigInteger modulus,
        PolynomialWorkBudget work,
        String stage
    ) {
        if (!source.ring().equals(product.ring())
                || modulus.signum() <= 0) {
            throw new HenselLifting.AlgorithmFailure(
                "HENSEL_CONGRUENCE_RING_OR_MODULUS_MISMATCH");
        }
        int count = Math.max(
            source.coefficientCount(),
            product.coefficientCount());
        for (int exponent = 0; exponent < count; exponent++) {
            work.consume(stage + ".coefficient-tests", 1);
            BigInteger difference = source.coefficient(exponent)
                .subtract(product.coefficient(exponent));
            if (difference.remainder(modulus).signum() != 0) {
                throw new HenselLifting.AlgorithmFailure(
                    "HENSEL_PRODUCT_CONGRUENCE_FAILED");
            }
        }
    }

    static void checkView(
        UnivariatePolynomialView<BigInteger> value,
        HenselLiftingPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        for (BigInteger coefficient : value.coefficients()) {
            work.consume(stage + ".coefficient-checks", 1);
            checkCoefficient(coefficient, policy);
        }
    }

    private static void checkCoefficient(
        BigInteger coefficient,
        HenselLiftingPolicy policy
    ) {
        if (coefficient.abs().bitLength()
                > policy.maxIntermediateCoefficientBitLength()) {
            throw new HenselLifting.RepresentationLimitReached(
                "HENSEL_INTERMEDIATE_COEFFICIENT_BIT_LENGTH_EXCEEDED");
        }
    }

    private static ArrayList<BigInteger> zeros(int count) {
        ArrayList<BigInteger> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(BigInteger.ZERO);
        }
        return result;
    }

    static List<SparsePolynomial<BigInteger>> toSparse(
        List<UnivariatePolynomialView<BigInteger>> factors
    ) {
        return factors.stream()
            .map(UnivariatePolynomialView::toSparsePolynomial)
            .toList();
    }
}
