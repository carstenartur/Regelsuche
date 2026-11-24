package de.regelsuche.math.algorithms.iteration;

import de.regelsuche.ast.iteration.AffineReduction.Form;
import de.regelsuche.math.algorithms.iteration.IterationPolynomial.Work;
import java.util.ArrayList;
import java.util.List;

final class AffineDomains {
    static final int MAX_COORDINATE = 1_048_575;

    private AffineDomains() {}

    /** An integer inequality in at most two axes after fixing the size parameter. */
    record Clause(long constant, long first, long second) {
        long afterFirst(int value) {
            return constant + first * value;
        }
    }

    record Range(int lower, int upper) {
        boolean isEmpty() {
            return lower > upper;
        }
    }

    static List<Clause> instantiate(List<Form> forms, int rank, int parameter) {
        return forms.stream().map(form -> new Clause(form.constant() + (long) form.parameter() * parameter,
            form.coordinates().getFirst(), rank == 2 ? form.coordinates().getLast() : 0)).toList();
    }

    /** Fourier–Motzkin projection gives an outer range; second-axis integer rounding then filters it exactly. */
    static Range firstRange(List<Clause> source, Work work) {
        var projected = new ArrayList<Clause>();
        for (var clause : source) {
            work.charge(1);
            if (clause.second() == 0) {
                projected.add(clause);
            } else if (clause.second() > 0) {
                addProjected(clause, source, projected, work);
            }
        }
        return range(projected, 0, false, work);
    }

    private static void addProjected(Clause lower, List<Clause> source, List<Clause> result, Work work) {
        for (var upper : source) {
            work.charge(1);
            if (upper.second() < 0) {
                long p = lower.second(), q = -upper.second();
                result.add(new Clause(q * lower.constant() + p * upper.constant(),
                    q * lower.first() + p * upper.first(), 0));
            }
        }
    }

    static Range secondRange(List<Clause> source, int first, Work work) {
        return range(source, first, true, work);
    }

    private static Range range(List<Clause> source, int first, boolean second, Work work) {
        long lower = Long.MIN_VALUE;
        long upper = Long.MAX_VALUE;
        for (var clause : source) {
            work.charge(1);
            long constant = second ? clause.afterFirst(first) : clause.constant();
            long slope = second ? clause.second() : clause.first();
            if (slope > 0) lower = Math.max(lower, -Math.floorDiv(constant, slope));
            else if (slope < 0) upper = Math.min(upper, Math.floorDiv(constant, -slope));
            else if (constant < 0) return new Range(0, -1);
        }
        if (lower > upper) return new Range(0, -1);
        if (lower < -MAX_COORDINATE || upper > MAX_COORDINATE)
            throw new IllegalArgumentException("UNBOUNDED_OR_LARGE_AFFINE_DOMAIN");
        return new Range((int) lower, (int) upper);
    }
}
