package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSupport.Work;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Evolution;
import de.regelsuche.math.algorithms.linalg.RecurrenceInvariantSynthesis.Transition;
import de.regelsuche.representation.RepresentationBridge.Budget;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Checks the source relationship that allows a previously proved step to be transported. */
final class RecurrenceInvariantTransport {
    private RecurrenceInvariantTransport() {}

    record Image(Polynomial polynomial, Evolution evolution) {}

    static Optional<Image> image(Transition from, Transition to, Polynomial polynomial, Evolution evolution, Work work) {
        int dimension = from.linear().columns();
        work.add(1);
        if (dimension != to.linear().columns()) return Optional.empty();

        boolean same = true;
        boolean opposite = true;
        for (int i = 0; i < dimension && (same || opposite); i++) {
            for (int j = 0; j < dimension && (same || opposite); j++) {
                var source = from.linear().get(i, j);
                var target = to.linear().get(i, j);
                if (same) {
                    work.add(1);
                    same = source.equals(target);
                }
                if (opposite) {
                    work.add(1);
                    opposite = source.negate().equals(target);
                }
            }
        }
        if (same) {
            work.add(dimension);
            if (from.offset().equals(to.offset())) return Optional.of(new Image(polynomial, evolution));
            return translation(from, to, work)
                .map(shift -> new Image(RecurrenceInvariantSupport.translate(polynomial, shift, work), evolution));
        }
        if (!opposite || !zeroOffsets(from, to, work)) return Optional.empty();
        return reversedSign(polynomial, evolution, work);
    }

    private static boolean zeroOffsets(Transition from, Transition to, Work work) {
        for (int i = 0; i < from.offset().dimension(); i++) {
            work.add(2);
            if (!from.offset().get(i).isZero() || !to.offset().get(i).isZero()) return false;
        }
        return true;
    }

    private static Optional<Image> reversedSign(Polynomial polynomial, Evolution evolution, Work work) {
        work.add(2L * polynomial.termCount());
        int parity = polynomial.totalDegree() & 1;
        for (var monomial : polynomial.terms().keySet()) {
            if ((monomial.totalDegree() & 1) != parity) return Optional.empty();
        }
        // For a definite parity, P(-Ax) = (-1)^parity P(Ax).
        if (parity == 1) evolution = evolution == Evolution.FIXED ? Evolution.ALTERNATING : Evolution.FIXED;
        return Optional.of(new Image(polynomial, evolution));
    }

    /** Solve (I-A)c=b_target-b_source via the kernel of the bordered exact matrix. */
    private static Optional<List<Rational>> translation(Transition from, Transition to, Work work) {
        int dimension = from.linear().columns();
        var rows = new ArrayList<List<Rational>>(dimension);
        var names = new ArrayList<String>(dimension + 1);
        var origins = new ArrayList<RowOrigin>(dimension);
        for (int i = 0; i < dimension; i++) {
            var row = new ArrayList<Rational>(dimension + 1);
            names.add("c" + i);
            origins.add(new RowOrigin(i, "translation coordinate " + i));
            for (int j = 0; j < dimension; j++) {
                work.add(1);
                var identity = i == j ? Rational.ONE : Rational.ZERO;
                row.add(identity.subtract(from.linear().get(i, j)));
            }
            work.add(4);
            row.add(from.offset().get(i).subtract(to.offset().get(i)));
            rows.add(row);
        }
        names.add("scale");
        var solved = new ExactRrefSolver().solveHomogeneous(new ExactMatrix(rows), names, origins,
            new Budget(work.remaining()));
        work.add(solved.work().consumedWorkUnits());
        if (solved.status() == ExactRrefSolver.Status.BUDGET_INCONCLUSIVE) {
            throw new RecurrenceInvariantSupport.Exhausted();
        }
        var reduction = solved.reduction().orElseThrow(() -> new IllegalStateException("translation reduction failed"));
        for (var vector : reduction.nullspaceBasis()) {
            work.add(1);
            var scale = vector.get(dimension);
            if (scale.isZero()) continue;
            work.add(dimension);
            var shift = vector.values().subList(0, dimension).stream().map(value -> value.divide(scale)).toList();
            checkTranslation(from, to, shift, work);
            return Optional.of(shift);
        }
        return Optional.empty();
    }

    private static void checkTranslation(Transition from, Transition to, List<Rational> shift, Work work) {
        for (int i = 0; i < shift.size(); i++) {
            work.add(2L + 2L * shift.size());
            var residual = shift.get(i).add(from.offset().get(i)).subtract(to.offset().get(i));
            for (int j = 0; j < shift.size(); j++) {
                residual = residual.subtract(from.linear().get(i, j).multiply(shift.get(j)));
            }
            if (!residual.isZero()) throw new IllegalStateException("invalid translation witness");
        }
    }
}
