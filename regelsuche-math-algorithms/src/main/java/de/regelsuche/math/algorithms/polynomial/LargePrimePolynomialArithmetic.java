package de.regelsuche.math.algorithms.polynomial;

import de.regelsuche.polynomial.PolynomialRing;
import de.regelsuche.polynomial.SparsePolynomial;
import de.regelsuche.polynomial.UnivariatePolynomialView;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Budgeted search arithmetic over the existing generic dense polynomial view. */
final class LargePrimePolynomialArithmetic {
    private final LargePrimeWorkBudget work;
    private final LargePrimeWorkBudget.Domain domain;
    private final PolynomialRing<BigInteger> ring;

    LargePrimePolynomialArithmetic(PolynomialRing<BigInteger> ring, LargePrimeWorkBudget.Domain domain,
                                   LargePrimeWorkBudget work) {
        this.ring = ring;
        this.domain = domain;
        this.work = work;
    }

    UnivariatePolynomialView<BigInteger> from(SparsePolynomial<BigInteger> source) {
        work.reserveVector(source.degree(0) + 1L, 4);
        work.consume("search.load", source.degree(0) + 1L);
        return UnivariatePolynomialView.from(source, work, "search.load");
    }

    UnivariatePolynomialView<BigInteger> one() { return of(List.of(BigInteger.ONE)); }
    UnivariatePolynomialView<BigInteger> x() { return of(List.of(BigInteger.ZERO, BigInteger.ONE)); }

    private UnivariatePolynomialView<BigInteger> of(List<BigInteger> coefficients) {
        work.reserveVector(coefficients.size(), 3);
        work.consume("search.construct", coefficients.size());
        return UnivariatePolynomialView.of(ring, coefficients, work, "search.construct");
    }

    UnivariatePolynomialView<BigInteger> monic(UnivariatePolynomialView<BigInteger> value) {
        work.consume("search.monic", 1);
        if (value.isZero() || value.leadingCoefficient().equals(BigInteger.ONE)) return value;
        work.reserveVector(value.coefficientCount(), 3);
        work.inversion("search.normalize-inverse");
        var result = value.monic(domain.field(), work, "search.normalize");
        work.consume("search.normalize-complete", 0);
        return result;
    }

    UnivariatePolynomialView<BigInteger> derivative(UnivariatePolynomialView<BigInteger> value) {
        work.reserveVector(value.coefficientCount(), 3);
        return value.derivative(work, "search.derivative");
    }

    UnivariatePolynomialView<BigInteger> subtract(UnivariatePolynomialView<BigInteger> left,
                                                 UnivariatePolynomialView<BigInteger> right) {
        work.reserveVector(Math.max(left.coefficientCount(), right.coefficientCount()), 3);
        return left.subtract(right, work, "search.subtract");
    }

    UnivariatePolynomialView<BigInteger> add(UnivariatePolynomialView<BigInteger> left,
                                            UnivariatePolynomialView<BigInteger> right) {
        work.reserveVector(Math.max(left.coefficientCount(), right.coefficientCount()), 3);
        return left.add(right, work, "search.add");
    }

    private UnivariatePolynomialView.DivisionResult<BigInteger> divide(UnivariatePolynomialView<BigInteger> value,
                                                                       UnivariatePolynomialView<BigInteger> monic) {
        // Three copies for each of quotient and remainder, before the generic
        // view allocates any coefficient array. All divisors here are monic.
        work.reserveVector(value.coefficientCount(), 6);
        return value.divideAndRemainderMonic(monic, work, "search.divide");
    }

    UnivariatePolynomialView<BigInteger> remainder(UnivariatePolynomialView<BigInteger> value,
                                                  UnivariatePolynomialView<BigInteger> modulus) {
        work.consume("search.remainder", 1);
        if (value.degree() < modulus.degree()) return value;
        return divide(value, modulus).remainder();
    }

    UnivariatePolynomialView<BigInteger> quotient(UnivariatePolynomialView<BigInteger> value,
                                                 UnivariatePolynomialView<BigInteger> divisor) {
        var result = divide(value, divisor);
        if (!result.remainder().isZero()) throw new ArithmeticException("inexact polynomial quotient");
        return result.quotient();
    }

    UnivariatePolynomialView<BigInteger> gcd(UnivariatePolynomialView<BigInteger> first,
                                            UnivariatePolynomialView<BigInteger> second) {
        while (!second.isZero()) {
            work.consume("search.gcd", 1);
            second = monic(second);
            var next = remainder(first, second);
            first = second;
            second = next;
        }
        return monic(first);
    }

    UnivariatePolynomialView<BigInteger> multiplyMod(UnivariatePolynomialView<BigInteger> left,
        UnivariatePolynomialView<BigInteger> right, UnivariatePolynomialView<BigInteger> modulus) {
        work.reserveVector(left.coefficientCount() + (long) right.coefficientCount(), 3);
        return remainder(left.multiply(right, work, "search.multiply"), modulus);
    }

    UnivariatePolynomialView<BigInteger> power(UnivariatePolynomialView<BigInteger> base, BigInteger exponent,
                                              UnivariatePolynomialView<BigInteger> modulus) {
        var result = one();
        var reduced = remainder(base, modulus);
        for (int bit = exponent.bitLength() - 1; bit >= 0; bit--) {
            work.consume("search.power-bit", 1);
            result = multiplyMod(result, result, modulus);
            if (exponent.testBit(bit)) result = multiplyMod(result, reduced, modulus);
        }
        return result;
    }

    UnivariatePolynomialView<BigInteger> randomBelowDegree(int degree, Random random) {
        work.reserveVector(degree, 4);
        var coefficients = new ArrayList<BigInteger>(degree);
        for (int i = 0; i < degree; i++) {
            BigInteger coefficient;
            do {
                work.consume("search.random-coefficient", (work.modulusBits() + 31L) / 32);
                coefficient = new BigInteger(work.modulusBits(), random);
            } while (coefficient.compareTo(domain.modulus()) >= 0);
            coefficients.add(coefficient);
        }
        return of(coefficients);
    }

    UnivariatePolynomialView<BigInteger> frobeniusRoot(UnivariatePolynomialView<BigInteger> value) {
        // A nonconstant p-th power has degree at least p. Only then may p
        // safely be narrowed to an exponent index, even for a huge modulus.
        if (domain.modulus().compareTo(BigInteger.valueOf(value.degree())) > 0)
            throw new ArithmeticException("nonconstant inseparable residual below characteristic");
        int characteristic = domain.modulus().intValueExact();
        work.reserveVector(value.degree() / characteristic + 1L, 4);
        var coefficients = new ArrayList<BigInteger>();
        for (int i = 0; i <= value.degree(); i++) {
            work.consume("search.frobenius-root", 1);
            if (i % characteristic == 0) coefficients.add(value.coefficient(i));
            else if (value.coefficient(i).signum() != 0)
                throw new ArithmeticException("invalid inseparable residual");
        }
        return of(coefficients);
    }

    SparsePolynomial<BigInteger> sparse(UnivariatePolynomialView<BigInteger> value) {
        work.reserveVector(value.coefficientCount(), 8);
        work.consume("search.issue-factor", value.coefficientCount());
        return value.toSparsePolynomial(work, "search.issue-factor");
    }
}
