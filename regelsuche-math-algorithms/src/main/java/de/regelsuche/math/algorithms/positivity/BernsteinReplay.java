package de.regelsuche.math.algorithms.positivity;

import de.regelsuche.scalar.ExactRational;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import static de.regelsuche.math.algorithms.positivity.BernsteinPositivity.*;
import static de.regelsuche.scalar.ExactRational.*;

/** Independent leaf identity test by exact interpolation on a unisolvent tensor grid. */
final class BernsteinReplay {
    private BernsteinReplay() {}

    static Verification verify(Request source, Certificate certificate, PositivityWork work) {
        try {
            int size = BernsteinTensors.size(source, work);
            BernsteinTensors.checkBox(source.box(), work);
            BernsteinTensors.checkBox(certificate.source().box(), work);
            if (!source.box().equals(certificate.source().box())
                    || !work.samePolynomial(source.polynomial(), certificate.source().polynomial())) {
                return new Verification(false, "SOURCE_MISMATCH", work.used);
            }
            work.terms(certificate.steps().size());
            var boxes = new ArrayDeque<List<Interval>>();
            boxes.push(source.box());
            long cells = size;
            for (var step : certificate.steps()) {
                work.charge(1);
                if (boxes.isEmpty()) return new Verification(false, "EXCESS_STEP", work.used);
                var box = boxes.pop();
                if (step instanceof Split(int axis)) {
                    if (axis < 0 || axis >= box.size()) return new Verification(false, "INVALID_AXIS", work.used);
                    cells += 2L * size;
                    work.terms(cells);
                    var parent = box.get(axis);
                    var midpoint = work.div(work.add(parent.lower(), parent.upper()), integer(2));
                    var child = box.toArray(Interval[]::new);
                    child[axis] = new Interval(midpoint, parent.upper());
                    boxes.push(List.of(child));
                    child[axis] = new Interval(parent.lower(), midpoint);
                    boxes.push(List.of(child));
                } else if (step instanceof Leaf leaf) {
                    if (!box.equals(leaf.box()) || leaf.coefficients().size() != size) {
                        return new Verification(false, "LEAF_SHAPE", work.used);
                    }
                    for (var value : leaf.coefficients()) {
                        int sign = work.check(value).signum();
                        if (sign < 0 || (sign == 0 && certificate.claim() == Claim.STRICTLY_POSITIVE)) {
                            return new Verification(false, "COEFFICIENT_SIGN", work.used);
                        }
                    }
                    if (!identity(source.polynomial(), leaf, work)) return new Verification(false, "LEAF_IDENTITY", work.used);
                }
            }
            work.charge(0);
            return new Verification(boxes.isEmpty(), boxes.isEmpty() ? "VERIFIED" : "INCOMPLETE_PARTITION", work.used);
        } catch (PositivityWork.Stop stop) {
            return new Verification(false, stop.getMessage(), work.used);
        }
    }

    private record Node(ExactRational coordinate, ExactRational[] weights) {}
    private static Node node(Interval interval, int index, int degree, PositivityWork work) {
        work.charge(degree + 1L);
        var weights = new ExactRational[degree + 1];
        if (index == 0 || index == degree) {
            Arrays.fill(weights, ZERO);
            weights[index] = ONE;
            return new Node(index == 0 ? interval.lower() : interval.upper(), weights);
        }
        var t = work.div(integer(index), integer(degree));
        var s = work.sub(ONE, t);
        var coordinate = work.add(work.mul(s, interval.lower()), work.mul(t, interval.upper()));
        weights[0] = work.pow(s, degree);
        var ratio = work.div(t, s);
        for (int i = 1; i <= degree; i++) {
            var factor = work.div(integer(degree-i+1), integer(i));
            weights[i] = work.mul(weights[i-1], work.mul(factor, ratio));
        }
        return new Node(coordinate, weights);
    }

    static boolean identity(SparsePolynomial p, Leaf leaf, PositivityWork work) {
        int nx = p.degrees().getFirst();
        int ny = p.degreeY();
        for (int i = 0; i <= nx; i++) {
            var x = node(leaf.box().getFirst(), i, nx, work);
            for (int j = 0; j <= ny; j++) {
                var y = leaf.box().size() == 1 ? new Node(ZERO, new ExactRational[]{ONE})
                    : node(leaf.box().get(1), j, ny, work);
                var value = ZERO;
                for (var term : p.terms().entrySet()) {
                    var xPower = work.pow(x.coordinate(), term.getKey().x());
                    var yPower = work.pow(y.coordinate(), term.getKey().y());
                    value = work.add(value, work.mul(term.getValue(), work.mul(xPower, yPower)));
                }
                var represented = ZERO;
                for (int k = 0; k <= nx; k++) {
                    for (int l = 0; l <= ny; l++) {
                        var coefficient = leaf.coefficients().get(k * (ny + 1) + l);
                        var weight = work.mul(x.weights()[k], y.weights()[l]);
                        represented = work.add(represented, work.mul(coefficient, weight));
                    }
                }
                if (!represented.equals(value)) return false;
            }
        }
        return true;
    }
}
