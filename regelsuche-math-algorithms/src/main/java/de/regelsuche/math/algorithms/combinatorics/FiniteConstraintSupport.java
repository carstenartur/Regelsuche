package de.regelsuche.math.algorithms.combinatorics;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import java.math.BigInteger;

/** Shared shape and integer-cost primitives; no elimination or certificate decisions. */
final class FiniteConstraintSupport {
    static final int MAX_WIDTH = 20;

    private FiniteConstraintSupport() {}

    static long inspect(FiniteConstraintCounter.Problem problem, CertificateWork work) {
        long cells = problem.variableCount() + 2L * problem.fixed().size() + problem.factors().size();
        work.cells(cells);
        for (var factor : problem.factors()) {
            cells += factor.values().size() + factor.variables().size(); work.cells(cells);
            work.charge(1L + factor.variables().size());
            for (BigInteger value : factor.values()) work.check(value);
        }
        work.charge(1L + problem.fixed().size());
        return cells;
    }

    static int tableSize(int width, CertificateWork work) {
        requireWidth(width);
        int size = 1 << width; work.cells(size); return size;
    }

    static void requireWidth(int width) {
        if (width > MAX_WIDTH) throw new CertificateWork.Stop("WIDTH_LIMIT");
    }

    static BigInteger add(BigInteger a, BigInteger b, CertificateWork work) {
        work.charge(1); work.check(a); work.check(b); return work.check(a.add(b));
    }

    static BigInteger multiply(BigInteger a, BigInteger b, CertificateWork work) {
        work.charge(1); work.check(a); work.check(b); return work.check(a.multiply(b));
    }
}
