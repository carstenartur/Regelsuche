package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.scalar.ExactRational;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded sparse arithmetic for Gram identities and simultaneous polynomial substitutions. */
final class SosPolynomials {
    static final Polynomial ONE = Polynomial.constant(Rational.ONE);
    private SosPolynomials() {}

    static void validate(SosSynthesis.Request request, CertificateWork work) {
        long targetSize = polynomialSize(request.target(), work);
        var template = request.template();
        long size = targetSize + basisSize(template.basis(), work);
        work.cells(template.premises().size());
        for (var premise : template.premises()) {
            size += basisSize(premise.basis(), work) + polynomialSize(premise.polynomial(), work) + 1;
            work.cells(size);
        }
        work.cells(size + template.freeValues().size());
        for (var value : template.freeValues()) work.check(value);
    }

    static long basisSize(List<Monomial> basis, CertificateWork work) {
        long size = basis.size();
        work.cells(size);
        for (var monomial : basis) {
            size += monomialSize(monomial, work);
            work.cells(size);
        }
        return size;
    }

    static long polynomialSize(Polynomial source, CertificateWork work) {
        long size = source.termCount();
        work.cells(size);
        for (var term : source.terms().entrySet()) {
            size += monomialSize(term.getKey(), work);
            work.cells(size);
            work.check(term.getValue().numerator());
            work.check(term.getValue().denominator());
        }
        return size;
    }

    private static long monomialSize(Monomial monomial, CertificateWork work) {
        work.cells(monomial.powers().size());
        work.charge(1L + monomial.powers().size());
        return monomial.powers().size();
    }

    static Monomial times(Monomial a, Monomial b, CertificateWork work) {
        work.cells((long)a.powers().size() + b.powers().size());
        work.charge(1L + a.powers().size());
        for (var power : b.powers().entrySet()) {
            work.charge(1);
            if (power.getValue() > Integer.MAX_VALUE-a.exponentOf(power.getKey())) {
                throw new CertificateWork.Stop("EXPONENT_LIMIT");
            }
        }
        return a.multiply(b);
    }

    /** Count retained monomial exponents as well as coefficients during sparse expansion. */
    static final class Accumulator {
        final Map<Monomial, ExactRational> terms = new HashMap<>();
        private long cells;

        void add(Monomial monomial, ExactRational value, CertificateWork work) {
            var previous = terms.get(monomial);
            var sum = work.add(previous == null ? ExactRational.ZERO : previous, value);
            long size = 1L + monomial.powers().size();
            if (sum.isZero()) {
                if (previous != null) {
                    terms.remove(monomial);
                    cells -= size;
                }
            } else {
                if (previous == null) {
                    work.cells(cells+size);
                    cells += size;
                }
                terms.put(monomial, sum);
            }
        }
    }

    static Polynomial fromTerms(Map<Monomial, ExactRational> terms, CertificateWork work) {
        var result = Polynomial.zero();
        for (var term : terms.entrySet()) {
            // The immutable polynomial API copies each accumulated term map.
            work.charge(1L + result.termCount());
            result = result.add(Polynomial.term(term.getKey(), Rational.fromExact(work.check(term.getValue()))));
        }
        work.charge(0);
        return result;
    }

    static Map<Monomial, ExactRational> product(Map<Monomial, ExactRational> a, Map<Monomial, ExactRational> b,
            CertificateWork work) {
        work.charge(1);
        var result = new Accumulator();
        for (var left : a.entrySet()) {
            work.charge(1);
            for (var right : b.entrySet()) {
                result.add(times(left.getKey(), right.getKey(), work), work.mul(left.getValue(), right.getValue()), work);
            }
        }
        return result.terms;
    }

    static Accumulator coefficients(Polynomial source, CertificateWork work) {
        var terms = new Accumulator();
        for (var entry : source.terms().entrySet()) terms.add(entry.getKey(), entry.getValue().exactValue(), work);
        return terms;
    }

    /** Computes V(x) - V(T(x)) - delta. Unassigned variables retain their original values. */
    static Polynomial decrease(Polynomial potential, Map<String, Polynomial> transition, ExactRational delta,
            CertificateWork work) {
        var result = coefficients(potential, work);
        var constant = Monomial.constant();
        result.add(constant, work.sub(ExactRational.ZERO, delta), work);
        for (var term : potential.terms().entrySet()) {
            var negative = work.sub(ExactRational.ZERO, term.getValue().exactValue());
            Map<Monomial, ExactRational> substituted = Map.of(constant, negative);
            for (var power : term.getKey().powers().entrySet()) {
                var image = transition.get(power.getKey());
                var factor = image == null ? Map.of(Monomial.variable(power.getKey()), ExactRational.ONE)
                    : coefficients(image, work).terms;
                for (int exponent = power.getValue(); exponent > 0; exponent >>>= 1) {
                    work.charge(1);
                    if ((exponent & 1) != 0) substituted = product(substituted, factor, work);
                    if (exponent > 1) factor = product(factor, factor, work);
                }
            }
            for (var item : substituted.entrySet()) result.add(item.getKey(), item.getValue(), work);
        }
        return fromTerms(result.terms, work);
    }
}
