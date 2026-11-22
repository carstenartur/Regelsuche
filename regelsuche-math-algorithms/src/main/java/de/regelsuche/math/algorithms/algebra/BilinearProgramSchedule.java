package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.algebra.BilinearProgram.Cost;
import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.scalar.ExactRational;
import java.util.List;

/** Ordered sparse dot products. An addition may overwrite its accumulator and a product its operands. */
final class BilinearProgramSchedule {
    private BilinearProgramSchedule() {}

    static ExactRational dot(List<ExactRational> form, List<ExactRational> values, CertificateWork work) {
        ExactRational accumulator = null;
        for (int i = 0; i < form.size(); i++) {
            work.charge(1);
            var coefficient = form.get(i);
            if (coefficient.isZero()) continue;
            var term = values.get(i);
            if (!coefficient.isOne()) term = work.mul(coefficient, term);
            accumulator = accumulator == null ? term : work.add(accumulator, term);
        }
        return accumulator == null ? ExactRational.ZERO : accumulator;
    }

    static Cost cost(BilinearProgram program, CertificateWork work) {
        int rank = program.u().size();
        long additions = 0;
        long scalings = 0;
        long peak = rank;
        for (int p = 0; p < rank; p++) {
            var left = profile(program.u().get(p), work);
            var right = profile(program.v().get(p), work);
            additions += left.additions() + right.additions();
            scalings += left.scalings() + right.scalings();
            peak = Math.max(peak, p + left.peak());
            peak = Math.max(peak, p + left.resultSlots() + right.peak());
        }
        for (var weights : program.w()) {
            var output = profile(weights, work);
            additions += output.additions();
            scalings += output.scalings();
            peak = Math.max(peak, rank + output.peak());
        }
        return new Cost(rank, additions, scalings, peak);
    }

    private record Profile(long additions, long scalings, int resultSlots, int peak) {}

    private static Profile profile(List<ExactRational> form, CertificateWork work) {
        int terms = 0;
        long scalings = 0;
        int peak = 0;
        int accumulatorSlots = 0;
        for (var coefficient : form) {
            work.charge(1);
            if (coefficient.isZero()) continue;
            boolean scale = !coefficient.isOne();
            if (scale) {
                scalings++;
                peak = Math.max(peak, accumulatorSlots + 1);
            }
            terms++;
            if (scale || terms > 1) accumulatorSlots = 1;
            peak = Math.max(peak, accumulatorSlots);
        }
        return new Profile(Math.max(0, terms - 1), scalings, accumulatorSlots, peak);
    }
}
