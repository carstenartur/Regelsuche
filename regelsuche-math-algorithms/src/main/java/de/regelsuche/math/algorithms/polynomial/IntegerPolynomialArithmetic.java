package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.BigIntegerDomain;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

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
        return ZassenhausDivisorEnumeration.positiveDivisors(
            value,
            policy,
            work);
    }

    static List<UnivariatePolynomialView<BigInteger>> monicLiftedFactors(
        SparsePolynomial<BigInteger> source,
        HenselLiftingResult lifting,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        return ZassenhausCandidateArithmetic.monicLiftedFactors(
            source,
            lifting,
            policy,
            work);
    }

    static UnivariatePolynomialView<BigInteger> subsetCandidate(
        List<UnivariatePolynomialView<BigInteger>> modularFactors,
        int[] subset,
        BigInteger leadingDivisor,
        BigInteger modulus,
        ZassenhausRecombinationPolicy policy,
        PolynomialWorkBudget work
    ) {
        return ZassenhausCandidateArithmetic.subsetCandidate(
            modularFactors,
            subset,
            leadingDivisor,
            modulus,
            policy,
            work);
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
