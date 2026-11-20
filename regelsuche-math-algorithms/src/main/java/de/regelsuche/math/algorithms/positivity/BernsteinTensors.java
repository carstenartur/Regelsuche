package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.List;
import static de.regelsuche.math.algorithms.positivity.BernsteinPositivity.*;
import static de.regelsuche.scalar.ExactRational.*;

/** Power-basis conversion by Bernstein multiplication and degree elevation. */
final class BernsteinTensors {
    private BernsteinTensors() {}
    static final ExactRational TWO = integer(2);

    static int size(Request source, PositivityWork work) {
        long size = (1L + source.polynomial().degrees().getFirst()) * (1L + source.polynomial().degreeY());
        work.terms(size);
        return (int) size;
    }

    static void checkBox(List<Interval> box, PositivityWork work) {
        for (var interval : box) {
            work.check(interval.lower());
            work.check(interval.upper());
        }
    }

    static List<Interval> halfBox(List<Interval> box, int axis, boolean right, PositivityWork work) {
        var interval = box.get(axis);
        var midpoint = work.div(work.add(interval.lower(), interval.upper()), TWO);
        var result = box.toArray(Interval[]::new);
        result[axis] = right ? new Interval(midpoint, interval.upper()) : new Interval(interval.lower(), midpoint);
        return List.of(result);
    }

    static ExactRational[] root(Request source, int size, PositivityWork work) {
        work.charge(size);
        var coefficients = new ExactRational[size];
        Arrays.fill(coefficients, ZERO);
        var p = source.polynomial();
        for (var term : p.terms().entrySet()) {
            var exponent = term.getKey();
            var x = monomial(p.degrees().getFirst(), exponent.x(), source.box().getFirst(), work);
            var y = source.box().size() == 1 ? new ExactRational[]{ONE}
                : monomial(p.degreeY(), exponent.y(), source.box().get(1), work);
            for (int i = 0; i < x.length; i++) {
                var scaled = work.mul(term.getValue(), x[i]);
                for (int j = 0; j < y.length; j++) {
                    int k = i * y.length + j;
                    coefficients[k] = work.add(coefficients[k], work.mul(scaled, y[j]));
                }
            }
        }
        return coefficients;
    }

    // Multiply by x exactly "exponent" times, then by 1 to elevate to the declared degree.
    private static ExactRational[] monomial(int degree, int exponent, Interval interval, PositivityWork work) {
        work.charge(degree + 1L);
        var row = new ExactRational[degree + 1];
        row[0] = ONE;
        for (int n = 1; n <= degree; n++) {
            var lo = n <= exponent ? interval.lower() : ONE;
            var hi = n <= exponent ? interval.upper() : ONE;
            for (int i = n; i >= 0; i--) {
                var left = i == n ? ZERO : work.mul(integer(n - i), work.mul(lo, row[i]));
                var right = i == 0 ? ZERO : work.mul(integer(i), work.mul(hi, row[i - 1]));
                row[i] = work.div(work.add(left, right), integer(n));
            }
        }
        return row;
    }

    record Halves(ExactRational[] left, ExactRational[] right) {}

    static Halves split(ExactRational[] grid, int degreeX, int degreeY, int axis, PositivityWork work) {
        int n = axis == 0 ? degreeX : degreeY;
        int stride = axis == 0 ? degreeY + 1 : 1;
        int lines = axis == 0 ? degreeY + 1 : degreeX + 1;
        work.charge(2L * grid.length + n + 1);
        var left = new ExactRational[grid.length];
        var right = new ExactRational[grid.length];
        var row = new ExactRational[n+1];
        for (int line = 0; line < lines; line++) {
            int base = axis == 0 ? line : line * (degreeY+1);
            for (int i = 0; i <= n; i++) row[i] = grid[base+i*stride];
            for (int level = 0; level <= n; level++) {
                left[base+level*stride] = row[0];
                right[base+(n-level)*stride] = row[n-level];
                for (int i = 0; i < n-level; i++) row[i] = work.div(work.add(row[i], row[i+1]), TWO);
            }
        }
        return new Halves(left, right);
    }
}
