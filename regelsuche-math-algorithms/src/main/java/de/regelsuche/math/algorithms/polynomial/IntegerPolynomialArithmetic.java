package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Exact bounded integer-polynomial operations used by recombination. */
final class IntegerPolynomialArithmetic {
    private IntegerPolynomialArithmetic() {
    }

    static BigInteger coefficientBound(
        SparsePolynomial<BigInteger> source,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        requireIntegerUnivariate(source);
        BigInteger squaredNorm = BigInteger.ZERO;
        for (BigInteger coefficient : source.terms().values()) {
            work.consume("zassenhaus.bound.square", 1);
            BigInteger square = multiplyChecked(
                coefficient,
                coefficient,
                policy,
                "ZASSENHAUS_BOUND_BIT_LENGTH_EXCEEDED");
            work.consume("zassenhaus.bound.sum", 1);
            squaredNorm = addChecked(
                squaredNorm,
                square,
                policy,
                "ZASSENHAUS_BOUND_BIT_LENGTH_EXCEEDED");
        }
        work.consume("zassenhaus.bound.sqrt", 1);
        BigInteger norm = ceilSqrt(squaredNorm);
        work.consume("zassenhaus.bound.degree-scale", 1);
        return multiplyChecked(
            BigInteger.ONE.shiftLeft(Math.max(0, source.degree(0))),
            norm,
            policy,
            "ZASSENHAUS_BOUND_BIT_LENGTH_EXCEEDED");
    }

    static int minimumHenselExponent(
        int prime,
        BigInteger coefficientBound,
        ZassenhausRecombinationPolicy policy
    ) {
        if (prime < 2 || coefficientBound.signum() < 0) {
            throw new IllegalArgumentException(
                "invalid Hensel precision inputs");
        }
        BigInteger threshold = coefficientBound.shiftLeft(1);
        BigInteger modulus = BigInteger.ONE;
        BigInteger primeValue = BigInteger.valueOf(prime);
        for (int exponent = 1;
                exponent <= policy.maxHenselExponent();
                exponent++) {
            if ((long) modulus.bitLength()
                    + primeValue.bitLength() - 1L
                    > policy.maxModulusBitLength()) {
                throw new LimitReached(
                    "ZASSENHAUS_MODULUS_BIT_LENGTH_POLICY_EXCEEDED");
            }
            modulus = modulus.multiply(primeValue);
            if (modulus.bitLength() > policy.maxModulusBitLength()) {
                throw new LimitReached(
                    "ZASSENHAUS_MODULUS_BIT_LENGTH_POLICY_EXCEEDED");
            }
            if (modulus.compareTo(threshold) > 0) {
                return exponent;
            }
        }
        throw new LimitReached(
            "ZASSENHAUS_HENSEL_EXPONENT_POLICY_EXCEEDED");
    }

    static List<BigInteger> positiveDivisors(
        BigInteger value,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        BigInteger positive = value.abs();
        if (positive.signum() == 0) {
            throw new AlgorithmFailure(
                "ZASSENHAUS_ZERO_LEADING_COEFFICIENT");
        }
        TreeSet<BigInteger> divisors = new TreeSet<>();
        BigInteger limit = positive.sqrt();
        BigInteger candidate = BigInteger.ONE;
        long tests = 0;
        while (candidate.compareTo(limit) <= 0) {
            if (tests >= policy.maxLeadingDivisorTests()) {
                throw new LimitReached(
                    "ZASSENHAUS_LEADING_DIVISOR_TEST_LIMIT_EXCEEDED");
            }
            tests++;
            work.consume("zassenhaus.leading-divisor-tests", 1);
            BigInteger[] division = positive.divideAndRemainder(candidate);
            if (division[1].signum() == 0) {
                divisors.add(candidate);
                divisors.add(division[0]);
            }
            candidate = candidate.add(BigInteger.ONE);
        }
        return List.copyOf(divisors);
    }

    static List<UnivariatePolynomialView<BigInteger>> monicLiftedFactors(
        SparsePolynomial<BigInteger> source,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        requireIntegerUnivariate(source);
        if (!lifting.completed()
                || lifting.factors().isEmpty()
                || lifting.factors().size() > policy.maxAcceptedFactors()) {
            throw new AlgorithmFailure(
                "ZASSENHAUS_INVALID_HENSEL_FACTOR_SET");
        }
        BigInteger modulus = lifting.targetModulus();
        if (modulus.signum() <= 0
                || modulus.bitLength() > policy.maxModulusBitLength()) {
            throw new LimitReached(
                "ZASSENHAUS_MODULUS_BIT_LENGTH_POLICY_EXCEEDED");
        }

        ArrayList<UnivariatePolynomialView<BigInteger>> result =
            new ArrayList<>(lifting.factors().size());
        for (int index = 0; index < lifting.factors().size(); index++) {
            SparsePolynomial<BigInteger> lifted = lifting.factors().get(index);
            if (!source.ring().equals(lifted.ring())
                    || lifted.isConstant()) {
                throw new AlgorithmFailure(
                    "ZASSENHAUS_HENSEL_FACTOR_RING_OR_SHAPE_MISMATCH");
            }
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
                throw new AlgorithmFailure(
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
                    "zassenhaus.unanchor.factor-" + index);
                BigInteger centered = centeredResidue(
                    normalized,
                    modulus);
                requireCoefficientWithinPolicy(centered, policy);
                coefficients.add(centered);
            }
            UnivariatePolynomialView<BigInteger> monic =
                UnivariatePolynomialView.of(source.ring(), coefficients);
            work.consume("zassenhaus.unanchor.monic-tests", 1);
            if (monic.isConstant()
                    || !BigInteger.ONE.equals(
                        monic.leadingCoefficient())) {
                throw new AlgorithmFailure(
                    "ZASSENHAUS_MONIC_LIFT_RECONSTRUCTION_FAILED");
            }
            result.add(monic);
        }
        return List.copyOf(result);
    }

    static UnivariatePolynomialView<BigInteger> subsetCandidate(
        List<UnivariatePolynomialView<BigInteger>> modularFactors,
        int[] subset,
        BigInteger leadingDivisor,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        if (modularFactors.isEmpty()
                || subset.length == 0
                || leadingDivisor.signum() <= 0
                || modulus.compareTo(BigInteger.ONE) <= 0) {
            throw new IllegalArgumentException(
                "invalid Zassenhaus subset candidate input");
        }
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
            UnivariatePolynomialView.of(first.ring(), coefficients);
        work.consume("zassenhaus.subset-leading-tests", 1);
        if (candidate.isZero()
                || !candidate.leadingCoefficient().equals(
                    leadingDivisor)) {
            throw new AlgorithmFailure(
                "ZASSENHAUS_SUBSET_LEADING_COEFFICIENT_MISMATCH");
        }
        return candidate;
    }

    static ExactDivision exactDivide(
        UnivariatePolynomialView<BigInteger> dividend,
        UnivariatePolynomialView<BigInteger> divisor,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameIntegerRing(dividend, divisor);
        if (divisor.isZero()) {
            throw new ArithmeticException(
                "integer polynomial division by zero");
        }
        if (dividend.isZero()) {
            return new ExactDivision(
                true,
                UnivariatePolynomialView.zero(dividend.ring()));
        }
        if (dividend.degree() < divisor.degree()) {
            return new ExactDivision(false, null);
        }

        ArrayList<BigInteger> remainder =
            new ArrayList<>(dividend.coefficients());
        ArrayList<BigInteger> quotient = zeros(
            dividend.degree() - divisor.degree() + 1);
        int remainderDegree = trimmedDegree(remainder);
        BigInteger divisorLeading = divisor.leadingCoefficient();

        while (remainderDegree >= divisor.degree()) {
            work.consume(stage + ".leading-divisions", 1);
            BigInteger[] division = remainder.get(remainderDegree)
                .divideAndRemainder(divisorLeading);
            if (division[1].signum() != 0) {
                return new ExactDivision(false, null);
            }
            BigInteger scale = division[0];
            int shift = remainderDegree - divisor.degree();
            quotient.set(
                shift,
                addChecked(
                    quotient.get(shift),
                    scale,
                    policy,
                    "ZASSENHAUS_DIVISION_COEFFICIENT_LIMIT_EXCEEDED"));
            for (int exponent = 0;
                    exponent <= divisor.degree();
                    exponent++) {
                work.consume(stage + ".coefficient-updates", 2);
                BigInteger product = multiplyChecked(
                    scale,
                    divisor.coefficient(exponent),
                    policy,
                    "ZASSENHAUS_DIVISION_COEFFICIENT_LIMIT_EXCEEDED");
                int target = shift + exponent;
                remainder.set(
                    target,
                    addChecked(
                        remainder.get(target),
                        product.negate(),
                        policy,
                        "ZASSENHAUS_DIVISION_COEFFICIENT_LIMIT_EXCEEDED"));
            }
            remainderDegree = trimmedDegree(remainder);
        }

        work.consume(stage + ".remainder-tests", 1);
        if (remainderDegree >= 0) {
            return new ExactDivision(false, null);
        }
        UnivariatePolynomialView<BigInteger> exactQuotient =
            UnivariatePolynomialView.of(dividend.ring(), quotient);
        UnivariatePolynomialView<BigInteger> reconstructed =
            multiplyCheckedView(
                exactQuotient,
                divisor,
                policy,
                work,
                stage + ".product-verification");
        work.consume(stage + ".product-comparison", 1);
        if (!dividend.equals(reconstructed)) {
            throw new AlgorithmFailure(
                "ZASSENHAUS_EXACT_DIVISION_VERIFICATION_FAILED");
        }
        return new ExactDivision(true, exactQuotient);
    }

    static boolean isPrimitivePositive(
        UnivariatePolynomialView<BigInteger> polynomial,
        PolynomialWorkBudget work
    ) {
        if (polynomial.isZero()
                || polynomial.isConstant()
                || polynomial.leadingCoefficient().signum() <= 0) {
            return false;
        }
        BigInteger gcd = BigInteger.ZERO;
        for (BigInteger coefficient : polynomial.coefficients()) {
            work.consume("zassenhaus.candidate.primitive-gcd", 1);
            gcd = gcd.gcd(coefficient.abs());
        }
        work.consume("zassenhaus.candidate.primitive-test", 1);
        return BigInteger.ONE.equals(gcd);
    }

    static void verifyProduct(
        SparsePolynomial<BigInteger> source,
        List<SparsePolynomial<BigInteger>> factors,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        UnivariatePolynomialView<BigInteger> product =
            UnivariatePolynomialView.one(source.ring());
        for (SparsePolynomial<BigInteger> factor : factors) {
            product = multiplyCheckedView(
                product,
                UnivariatePolynomialView.from(factor),
                policy,
                work,
                "zassenhaus.verify.factor-products");
        }
        work.consume("zassenhaus.verify.product-comparison", 1);
        if (!UnivariatePolynomialView.from(source).equals(product)) {
            throw new AlgorithmFailure(
                "ZASSENHAUS_FACTOR_PRODUCT_MISMATCH");
        }
    }

    private static UnivariatePolynomialView<BigInteger> multiplyModulo(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameIntegerRing(left, right);
        if (left.isZero() || right.isZero()) {
            return UnivariatePolynomialView.zero(left.ring());
        }
        ArrayList<BigInteger> coefficients = zeros(
            left.degree() + right.degree() + 1);
        for (int leftExponent = 0;
                leftExponent < left.coefficientCount();
                leftExponent++) {
            for (int rightExponent = 0;
                    rightExponent < right.coefficientCount();
                    rightExponent++) {
                int exponent = leftExponent + rightExponent;
                BigInteger product = modularMultiply(
                    left.coefficient(leftExponent),
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
        return UnivariatePolynomialView.of(left.ring(), coefficients);
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
            throw new LimitReached(
                "ZASSENHAUS_INTERMEDIATE_COEFFICIENT_BIT_LENGTH_EXCEEDED");
        }
    }

    private static UnivariatePolynomialView<BigInteger> multiplyCheckedView(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work,
        String stage
    ) {
        requireSameIntegerRing(left, right);
        if (left.isZero() || right.isZero()) {
            return UnivariatePolynomialView.zero(left.ring());
        }
        ArrayList<BigInteger> coefficients = zeros(
            left.degree() + right.degree() + 1);
        for (int leftExponent = 0;
                leftExponent < left.coefficientCount();
                leftExponent++) {
            for (int rightExponent = 0;
                    rightExponent < right.coefficientCount();
                    rightExponent++) {
                work.consume(stage + ".multiply-add", 2);
                BigInteger product = multiplyChecked(
                    left.coefficient(leftExponent),
                    right.coefficient(rightExponent),
                    policy,
                    "ZASSENHAUS_INTERMEDIATE_COEFFICIENT_BIT_LENGTH_EXCEEDED");
                int exponent = leftExponent + rightExponent;
                coefficients.set(
                    exponent,
                    addChecked(
                        coefficients.get(exponent),
                        product,
                        policy,
                        "ZASSENHAUS_INTERMEDIATE_COEFFICIENT_BIT_LENGTH_EXCEEDED"));
            }
        }
        return UnivariatePolynomialView.of(left.ring(), coefficients);
    }

    private static BigInteger multiplyChecked(
        BigInteger left,
        BigInteger right,
        ZassenhausRecombinationPolicy policy,
        String detailCode
    ) {
        if (left.signum() != 0 && right.signum() != 0
                && (long) left.abs().bitLength()
                    + right.abs().bitLength() - 1L
                    > policy.maxIntermediateCoefficientBitLength()) {
            throw new LimitReached(detailCode);
        }
        BigInteger result = left.multiply(right);
        if (result.abs().bitLength()
                > policy.maxIntermediateCoefficientBitLength()) {
            throw new LimitReached(detailCode);
        }
        return result;
    }

    private static BigInteger addChecked(
        BigInteger left,
        BigInteger right,
        ZassenhausRecombinationPolicy policy,
        String detailCode
    ) {
        BigInteger result = left.add(right);
        if (result.abs().bitLength()
                > policy.maxIntermediateCoefficientBitLength()) {
            throw new LimitReached(detailCode);
        }
        return result;
    }

    private static BigInteger ceilSqrt(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                "square root input must not be negative");
        }
        BigInteger floor = value.sqrt();
        return floor.multiply(floor).equals(value)
            ? floor
            : floor.add(BigInteger.ONE);
    }

    private static int trimmedDegree(List<BigInteger> coefficients) {
        int degree = coefficients.size() - 1;
        while (degree >= 0
                && coefficients.get(degree).signum() == 0) {
            degree--;
        }
        return degree;
    }

    private static ArrayList<BigInteger> zeros(int size) {
        ArrayList<BigInteger> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(BigInteger.ZERO);
        }
        return result;
    }

    private static void requireIntegerUnivariate(
        SparsePolynomial<BigInteger> polynomial
    ) {
        if (!(polynomial.ring().coefficientDomain()
                instanceof BigIntegerDomain)
                || polynomial.ring().variableCount() != 1) {
            throw new IllegalArgumentException(
                "integer univariate polynomial required");
        }
    }

    private static void requireSameIntegerRing(
        UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right
    ) {
        if (!left.ring().equals(right.ring())
                || !(left.ring().coefficientDomain()
                    instanceof BigIntegerDomain)) {
            throw new IllegalArgumentException(
                "integer polynomial ring mismatch");
        }
    }

    record ExactDivision(
        boolean exact,
        UnivariatePolynomialView<BigInteger> quotient
    ) {
        ExactDivision {
            if (exact != (quotient != null)) {
                throw new IllegalArgumentException(
                    "exact division result is invalid");
            }
        }
    }

    static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        LimitReached(String detailCode) {
            this.detailCode = detailCode;
        }

        String detailCode() {
            return detailCode;
        }
    }

    static final class AlgorithmFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        AlgorithmFailure(String detailCode) {
            this.detailCode = detailCode;
        }

        String detailCode() {
            return detailCode;
        }
    }
}
