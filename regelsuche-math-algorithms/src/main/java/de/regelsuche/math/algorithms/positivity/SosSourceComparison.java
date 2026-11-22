package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import java.util.TreeMap;

/** Structural source binding for the validated sparse polynomials and ordered SOS templates. */
final class SosSourceComparison {
    private SosSourceComparison() {}

    static boolean template(SosSynthesis.Template a, SosSynthesis.Template b, CertificateWork work) {
        if (a == b) return true;
        if (a.premises().size() != b.premises().size() || !basis(a.basis(), b.basis(), work)
                || !values(a.freeValues(), b.freeValues(), work)) return false;
        for (int i = 0; i < a.premises().size(); i++) {
            work.charge(1);
            var left = a.premises().get(i);
            var right = b.premises().get(i);
            if (left.relation() != right.relation() || !basis(left.basis(), right.basis(), work)
                    || !polynomial(left.polynomial(), right.polynomial(), work)) return false;
        }
        return true;
    }

    static boolean values(List<ExactRational> a, List<ExactRational> b, CertificateWork work) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!work.check(a.get(i)).equals(b.get(i))) return false;
        }
        return true;
    }

    static boolean basis(List<Monomial> a, List<Monomial> b, CertificateWork work) {
        if (a == b) return true;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (compareMonomials(a.get(i), b.get(i), work) != 0) return false;
        }
        return true;
    }

    static boolean polynomial(Polynomial a, Polynomial b, CertificateWork work) {
        work.charge(1);
        if (a == b) return true;
        if (a.termCount() != b.termCount()) return false;
        // Polynomial terms are unordered. A checked tree lookup avoids a hash-bucket scan over
        // arbitrarily many colliding monomials with no budget checkpoints.
        work.cells(a.termCount());
        var index = new TreeMap<Monomial, Rational>((left, right) -> compareMonomials(left, right, work));
        for (var term : a.terms().entrySet()) {
            work.charge(1);
            index.put(term.getKey(), term.getValue());
        }
        for (var term : b.terms().entrySet()) {
            var coefficient = index.get(term.getKey());
            if (coefficient == null || !work.check(term.getValue().exactValue()).equals(coefficient.exactValue())) return false;
        }
        return true;
    }

    private static int compareMonomials(Monomial a, Monomial b, CertificateWork work) {
        work.charge(1);
        if (a == b) return 0;
        int count = Integer.compare(a.powers().size(), b.powers().size());
        if (count != 0) return count;
        // Monomial stores its variable/exponent entries in ascending variable order.
        var right = b.powers().entrySet().iterator();
        for (var left : a.powers().entrySet()) {
            work.charge(1);
            var other = right.next();
            int name = compareNames(left.getKey(), other.getKey(), work);
            if (name != 0) return name;
            int exponent = Integer.compare(left.getValue(), other.getValue());
            if (exponent != 0) return exponent;
        }
        return 0;
    }

    static int compareNames(String a, String b, CertificateWork work) {
        if (a == b) return 0;
        int common = Math.min(a.length(), b.length());
        for (int start = 0; start < common;) {
            work.charge(1);
            int end = start + Math.min(64, common - start);
            for (int i = start; i < end; i++) {
                int order = Character.compare(a.charAt(i), b.charAt(i));
                if (order != 0) return order;
            }
            start = end;
        }
        return Integer.compare(a.length(), b.length());
    }
}
