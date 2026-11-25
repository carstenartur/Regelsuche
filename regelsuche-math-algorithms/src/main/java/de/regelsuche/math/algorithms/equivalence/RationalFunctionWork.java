package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.math.algorithms.certificates.CertificateWork;

/** Optional per-invocation bounds around the existing exact polynomial operations. */
final class RationalFunctionWork {
    private static final int MAX_TERMS = 512;
    private static final int MAX_DEGREE = 128;
    private static final int MAX_BITS = 4096;
    private final CertificateWork work;

    RationalFunctionWork(CertificateWork work) { this.work = work; }

    void charge(long units) { if (work != null) work.charge(units); }

    void source(String text) {
        if (work == null) return;
        charge(0);
        if (text.length() > 16_384) throw new CertificateWork.Stop("SOURCE_LIMIT");
        charge(text.length());
        int structure = 0;
        for (int i = 0; i < text.length(); i++) {
            if ("+-*/^(),".indexOf(text.charAt(i)) >= 0 && ++structure > 256)
                throw new CertificateWork.Stop("SYNTAX_LIMIT");
        }
    }

    void cells(long count) {
        if (work != null) {
            work.cells(count);
            if (count > 4096) throw new CertificateWork.Stop("RATIONAL_STATE_LIMIT");
        }
    }

    Polynomial checked(Polynomial value) {
        if (work == null) return value;
        cells(value.termCount());
        if (value.termCount() > MAX_TERMS || value.totalDegree() > MAX_DEGREE)
            throw new CertificateWork.Stop("POLYNOMIAL_SIZE_LIMIT");
        bits(value);
        return value;
    }

    private int bits(Polynomial value) {
        int maximum = 0;
        for (var coefficient : value.terms().values()) {
            charge(1);
            int n = Math.max(coefficient.numerator().abs().bitLength(), coefficient.denominator().bitLength());
            requireBits(n);
            charge(1L + n / Long.SIZE);
            maximum = Math.max(maximum, n);
        }
        return maximum;
    }

    private void requireBits(long n) {
        if (n > MAX_BITS) throw new CertificateWork.Stop("COEFFICIENT_BIT_LIMIT");
    }

    Polynomial add(Polynomial left, Polynomial right, boolean subtract) {
        if (work != null) {
            charge(1L + left.termCount() + right.termCount());
            cells((long) left.termCount() + right.termCount());
            requireBits((long) bits(left) + bits(right) + 1);
        }
        return checked(subtract ? left.subtract(right) : left.add(right));
    }

    Polynomial multiply(Polynomial left, Polynomial right) {
        if (work != null) {
            long products = (long) left.termCount() * right.termCount();
            charge(1 + products);
            cells(products);
            if ((long) left.totalDegree() + right.totalDegree() > MAX_DEGREE)
                throw new CertificateWork.Stop("POLYNOMIAL_DEGREE_LIMIT");
            requireBits(Math.max(1, Math.min(left.termCount(), right.termCount()))
                * (1L + bits(left) + bits(right)));
        }
        return checked(left.multiply(right));
    }

    Polynomial power(Polynomial base, int exponent) {
        if (work == null) return base.pow(exponent);
        Polynomial result = Polynomial.constant(Rational.ONE);
        while (exponent != 0) {
            if ((exponent & 1) != 0) result = multiply(result, base);
            exponent >>>= 1;
            if (exponent != 0) base = multiply(base, base);
        }
        return result;
    }

    Polynomial monic(Polynomial value) {
        if (work == null) return value.monic(new GradedReverseLexOrder());
        checked(value);
        charge(value.termCount());
        if (value.isZero()) return value;
        var leading = value.leadingTerm(new GradedReverseLexOrder()).orElseThrow().coefficient();
        return multiply(value, Polynomial.constant(Rational.ONE.divide(leading)));
    }

    String text(Polynomial value) {
        checked(value);
        charge(1L + value.termCount() * (1L + value.totalDegree()));
        return value.toCanonicalString();
    }
}
