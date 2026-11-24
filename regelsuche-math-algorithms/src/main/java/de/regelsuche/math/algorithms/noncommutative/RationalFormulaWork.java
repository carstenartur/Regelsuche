package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.util.function.BooleanSupplier;

final class RationalFormulaWork {
    private static final int MAX_DEPTH = 64;
    final CertificateWork ledger;
    private long cells;

    RationalFormulaWork(IterationLimits limits, BooleanSupplier cancelled) {
        ledger = new CertificateWork(limits, cancelled);
    }

    void enter(int depth) {
        ledger.charge(1);
        if (depth > MAX_DEPTH) throw new CertificateWork.Stop("DEPTH_LIMIT");
    }

    /** Conservatively charges cumulative table and vector allocations in the call. */
    void reserve(long count) {
        ledger.cells(cells + count);
        ledger.charge(count);
        cells += count;
    }

    static RationalFormulaRealization.Status status(CertificateWork.Stop stopped) {
        return "CANCELLED".equals(stopped.getMessage())
            ? RationalFormulaRealization.Status.CANCELLED : RationalFormulaRealization.Status.RESOURCE_LIMIT;
    }
}
