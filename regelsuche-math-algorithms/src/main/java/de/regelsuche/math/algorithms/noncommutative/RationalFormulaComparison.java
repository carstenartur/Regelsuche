package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Certificate;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Assignment;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaVerifier.Matrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

/** Decides equality on the shared defined matrix domain around a supplied common scalar center. */
public final class RationalFormulaComparison {
    private RationalFormulaComparison() {}

    public enum Status { EQUAL_ON_SHARED_DOMAIN, DISTINCT, INVALID_EVIDENCE, INCONCLUSIVE }
    public record Binding(Certificate left, Certificate right, Assignment center) {
        public Binding {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
            Objects.requireNonNull(center);
        }
    }

    public sealed interface Evidence permits Invariant, Witness { Binding binding(); }

    /** Columns in the original, unshifted realization coordinates. */
    public record Invariant(Binding binding, List<List<ExactRational>> columns) implements Evidence {
        public Invariant { Objects.requireNonNull(binding); columns = columns.stream().map(List::copyOf).toList(); }
    }
    public record Witness(Binding binding, Assignment assignment) implements Evidence {
        public Witness { Objects.requireNonNull(binding); Objects.requireNonNull(assignment); }
    }
    public record Result(Status status, Optional<Evidence> evidence, String reason, long work) {}

    public static Result compare(RationalFormula left, RationalFormula right, Assignment center, IterationLimits limits,
                                 BooleanSupplier cancelled) {
        return run(left, right, center, null, limits, cancelled);
    }

    public static Result verify(RationalFormula left, RationalFormula right, Assignment center, Evidence evidence,
                                IterationLimits limits, BooleanSupplier cancelled) {
        return run(left, right, center, Objects.requireNonNull(evidence), limits, cancelled);
    }

    private static Result run(RationalFormula left, RationalFormula right, Assignment center, Evidence evidence,
                              IterationLimits limits, BooleanSupplier cancelled) {
        var work = new RationalFormulaWork(limits, cancelled);
        return run(left, right, center, evidence, null, work);
    }

    /** Reuse checked realizations and the enclosing search's single resource ledger. */
    static Result comparePrepared(RationalFormula left, RationalFormula right, Certificate leftCertificate,
                                  Certificate rightCertificate, Assignment center, RationalFormulaWork work) {
        return run(left, right, center, null, new Binding(leftCertificate, rightCertificate, center), work);
    }

    private static Result run(RationalFormula left, RationalFormula right, Assignment center, Evidence evidence,
                              Binding prepared, RationalFormulaWork work) {
        try {
            work.ledger.charge(0);
            if (center.order() != 1) return failure(Status.INCONCLUSIVE, "REQUIRES_SCALAR_CENTER", work);
            Binding binding;
            if (evidence == null) {
                binding = prepared != null ? prepared : new Binding(RationalFormulaRealization.build(left, work),
                    RationalFormulaRealization.build(right, work), center);
            } else {
                binding = evidence.binding();
                RationalFormulaVerifier.checkStructure(left, binding.left(), work);
                RationalFormulaVerifier.checkStructure(right, binding.right(), work);
                if (!sameCenter(center, binding.center(), work))
                    return failure(Status.INVALID_EVIDENCE, "CENTER_MISMATCH", work);
            }
            if (evaluate(left, binding.left(), center, work).isEmpty()
                || evaluate(right, binding.right(), center, work).isEmpty())
                return failure(Status.INCONCLUSIVE, "UNDEFINED_SCALAR_CENTER", work);
            var pencil = new CenteredDifferencePencil(binding, work);
            if (evidence == null) evidence = search(pencil, binding, work);
            boolean valid = switch (evidence) {
                case Invariant invariant -> checkInvariant(pencil, invariant.columns(), work);
                case Witness witness -> {
                    var a = evaluate(left, binding.left(), witness.assignment(), work);
                    var b = evaluate(right, binding.right(), witness.assignment(), work);
                    if (a.isEmpty() || b.isEmpty()) yield false;
                    work.ledger.charge(a.orElseThrow().entries().size());
                    yield !a.equals(b);
                }
            };
            work.ledger.charge(0);
            if (!valid) return failure(Status.INVALID_EVIDENCE, "FAILED_CERTIFICATE_EQUATIONS", work);
            return new Result(evidence instanceof Witness ? Status.DISTINCT : Status.EQUAL_ON_SHARED_DOMAIN,
                Optional.of(evidence), "", work.ledger.used());
        } catch (CertificateWork.Stop stopped) {
            return failure(Status.INCONCLUSIVE, stopped.getMessage(), work);
        } catch (RationalFormulaVerifier.Rejected rejected) {
            return failure(evidence == null ? Status.INCONCLUSIVE : Status.INVALID_EVIDENCE, rejected.getMessage(), work);
        }
    }

    private static Result failure(Status status, String reason, RationalFormulaWork work) {
        return new Result(status, Optional.empty(), reason, work.ledger.used());
    }

    private static boolean sameCenter(Assignment a, Assignment b, RationalFormulaWork work) {
        if (a.order() != b.order() || a.variables().size() != b.variables().size()) return false;
        work.ledger.cells(a.variables().size());
        for (var entry : a.variables().entrySet()) {
            int levels = Integer.SIZE - Integer.numberOfLeadingZeros(b.variables().size());
            work.ledger.charge(1L + 2L * levels);
            var rhs = b.variables().get(entry.getKey());
            if (entry.getValue().order() != 1 || rhs == null || rhs.order() != 1) return false;
            if (!work.ledger.check(entry.getValue().entries().getFirst())
                    .equals(work.ledger.check(rhs.entries().getFirst()))) return false;
        }
        return true;
    }

    private static Optional<Matrix> evaluate(RationalFormula formula, Certificate certificate, Assignment point,
                                             RationalFormulaWork work) {
        return new RationalFormulaEvaluation(formula, certificate, point, work).check().value();
    }

    /** Parent is the suffix of the word: applying A_i prepends i. */
    private record Path(int variable, Path suffix, int length) {}
    private record Reachable(ExactRational[] column, Path path) {}

    private static Evidence search(CenteredDifferencePencil pencil, Binding binding, RationalFormulaWork work) {
        var inverse = pencil.inverseCenter();
        var initial = pencil.apply(inverse, pencil.v);
        var span = new Span(pencil.order, work);
        var queue = new ArrayDeque<Reachable>();
        var columns = new ArrayList<List<ExactRational>>();
        if (span.insert(initial)) queue.add(new Reachable(initial, null));
        while (!queue.isEmpty()) {
            var state = queue.remove();
            if (!pencil.output(state.column()).isZero()) return new Witness(binding, point(binding.center(), state.path(), work));
            work.reserve(pencil.order);
            columns.add(List.of(state.column()));
            for (int id : pencil.coefficients.keySet()) {
                var next = pencil.apply(inverse, pencil.negativeCoefficient(id, state.column()));
                if (span.insert(next)) {
                    work.reserve(2);
                    var path = new Path(id, state.path(), state.path() == null ? 1 : state.path().length() + 1);
                    queue.add(new Reachable(next, path));
                }
            }
        }
        work.reserve((long) columns.size() * (pencil.order + 1));
        return new Invariant(binding, columns);
    }

    private static Assignment point(Assignment center, Path word, RationalFormulaWork work) {
        int size = word == null ? 1 : word.length() + 1;
        var variables = new TreeMap<Integer, Matrix>();
        for (var entry : center.variables().entrySet()) {
            work.reserve(2L * size * size + 2);
            var values = new ExactRational[size * size];
            Arrays.fill(values, ZERO);
            for (int row = 0; row < size; row++) values[row * size + row] = entry.getValue().entries().getFirst();
            int row = 0;
            for (var path = word; path != null; path = path.suffix(), row++) {
                work.ledger.charge(1);
                if (path.variable() == entry.getKey()) values[row * size + row + 1] = ONE;
            }
            variables.put(entry.getKey(), new Matrix(size, Arrays.asList(values)));
        }
        return new Assignment(size, variables);
    }

    /** Check v in L(c)B, all -L_i B in L(c)B, and uB=0 directly in the original pencil. */
    private static boolean checkInvariant(CenteredDifferencePencil pencil, List<List<ExactRational>> columns,
                                          RationalFormulaWork work) {
        if (columns.size() > pencil.order) return false;
        var images = new Span(pencil.order, work);
        for (var column : columns) {
            if (column.size() != pencil.order) return false;
            work.reserve(column.size());
            var values = column.toArray(ExactRational[]::new);
            for (var value : values) work.ledger.check(value);
            if (!pencil.output(values).isZero()) return false;
            images.insert(pencil.apply(pencil.center, values));
        }
        if (!images.contains(pencil.v)) return false;
        for (var column : columns) {
            work.reserve(column.size());
            var values = column.toArray(ExactRational[]::new);
            for (int id : pencil.coefficients.keySet())
                if (!images.contains(pencil.negativeCoefficient(id, values))) return false;
        }
        return true;
    }

    /** Incremental exact row echelon form, indexed by the first nonzero coordinate. */
    private static final class Span {
        private final ExactRational[][] pivots;
        private final RationalFormulaWork work;

        Span(int dimension, RationalFormulaWork work) {
            this.work = work;
            work.reserve(dimension);
            pivots = new ExactRational[dimension][];
        }

        boolean insert(ExactRational[] vector) { return reduce(vector, true); }
        boolean contains(ExactRational[] vector) { return !reduce(vector, false); }

        private boolean reduce(ExactRational[] vector, boolean insert) {
            work.reserve(vector.length);
            var reduced = vector.clone();
            for (int i = 0; i < reduced.length; i++) {
                work.ledger.charge(1);
                var scale = reduced[i];
                if (scale.isZero()) continue;
                var pivot = pivots[i];
                if (pivot == null) {
                    if (insert) {
                        for (int j = i + 1; j < reduced.length; j++) reduced[j] = work.ledger.div(reduced[j], scale);
                        reduced[i] = ONE;
                        pivots[i] = reduced;
                    }
                    return true;
                }
                for (int j = i + 1; j < reduced.length; j++) {
                    reduced[j] = work.ledger.sub(reduced[j], work.ledger.mul(scale, pivot[j]));
                }
            }
            return false;
        }
    }
}
