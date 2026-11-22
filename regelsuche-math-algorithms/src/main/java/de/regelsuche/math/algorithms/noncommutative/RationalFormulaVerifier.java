package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Product;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Scalar;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Sum;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Certificate;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Constant;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Status;
import de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.VariableTerm;
import de.regelsuche.scalar.ExactRational;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

import static de.regelsuche.math.algorithms.noncommutative.RationalFormulaRealization.Status.INVALID_CERTIFICATE;
import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

/** Independent structural checking and exact evaluation at a concrete rational matrix assignment. */
public final class RationalFormulaVerifier {
    private RationalFormulaVerifier() {}

    /** Square rational matrix in row-major order. */
    public record Matrix(int order, List<ExactRational> entries) {
        public Matrix {
            entries = List.copyOf(entries);
            if (order < 1 || (long) order * order != entries.size()) {
                throw new IllegalArgumentException("INVALID_MATRIX_ORDER");
            }
        }
    }

    /** An explicit common order also covers formulas without variables. */
    public record Assignment(int order, Map<Integer, Matrix> variables) {
        public Assignment {
            if (order < 1) throw new IllegalArgumentException("INVALID_ASSIGNMENT_ORDER");
            var ordered = new TreeMap<Integer, Matrix>();
            for (var entry : variables.entrySet()) {
                if (ordered.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue())) != null) {
                    throw new IllegalArgumentException("DUPLICATE_VARIABLE_ID");
                }
            }
            variables = Collections.unmodifiableMap(ordered);
        }
    }

    public record Verification(Status status, String reason, long work) {}

    public record Evaluation(Status status, RationalFormula source, Assignment assignment, Optional<Matrix> value,
                             OptionalInt inverseOccurrence, String reason, long work) {}

    public static Verification verify(RationalFormula source, Certificate certificate, IterationLimits limits,
                                      BooleanSupplier cancelled) {
        var work = new RationalFormulaWork(limits, cancelled);
        try {
            checkStructure(source, certificate, work);
            work.ledger.charge(0);
            return new Verification(Status.VERIFIED, "", work.ledger.used());
        } catch (Rejected rejected) {
            return new Verification(rejected.status, rejected.getMessage(), work.ledger.used());
        } catch (CertificateWork.Stop stopped) {
            return new Verification(RationalFormulaWork.status(stopped), stopped.getMessage(), work.ledger.used());
        }
    }

    public static Evaluation evaluate(RationalFormula source, Certificate certificate, Assignment assignment,
                                      IterationLimits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(assignment, "assignment");
        var work = new RationalFormulaWork(limits, cancelled);
        try {
            checkStructure(source, certificate, work);
            return new RationalFormulaEvaluation(source, certificate, assignment, work).check();
        } catch (Rejected rejected) {
            return failedEvaluation(rejected.status, source, assignment, rejected.getMessage(), work);
        } catch (CertificateWork.Stop stopped) {
            return failedEvaluation(RationalFormulaWork.status(stopped), source, assignment, stopped.getMessage(), work);
        }
    }

    private static Evaluation failedEvaluation(Status status, RationalFormula source, Assignment assignment,
                                                String reason, RationalFormulaWork work) {
        return new Evaluation(status, source, assignment, Optional.empty(), OptionalInt.empty(), reason,
            work.ledger.used());
    }

    static void checkStructure(RationalFormula source, Certificate certificate, RationalFormulaWork work) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(certificate, "certificate");
        var check = new Structure(certificate, work);
        check.source(source, certificate.source(), 1);
        check.root(source);
    }

    static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final Status status;

        Rejected(Status status, String reason) {
            super(reason, null, false, false);
            this.status = status;
        }
    }

    private static void expect(boolean condition, String reason) {
        if (!condition) throw new Rejected(INVALID_CERTIFICATE, reason);
    }

    private record Masks(byte[] u, byte[] v) {
        int order() { return u.length; }
    }

    /** Checks the supplied entries in place. Only the {-1,0,1} selector masks are derived. */
    private static final class Structure {
        private final Certificate certificate;
        private final RationalFormulaWork work;
        private int nodeIndex;
        private int obligationIndex;

        Structure(Certificate certificate, RationalFormulaWork work) {
            this.certificate = certificate;
            this.work = work;
        }

        void source(RationalFormula expected, RationalFormula recorded, int depth) {
            work.enter(depth);
            if (expected == recorded) return;
            switch (expected) {
                case Scalar scalar -> expect(recorded instanceof Scalar other
                    && work.ledger.check(scalar.value()).equals(work.ledger.check(other.value())), "SOURCE_SCALAR");
                case Variable variable -> expect(recorded instanceof Variable other && variable.id() == other.id(),
                    "SOURCE_VARIABLE");
                case Sum sum -> {
                    if (!(recorded instanceof Sum other)) throw new Rejected(INVALID_CERTIFICATE, "SOURCE_SUM");
                    source(sum.left(), other.left(), depth + 1);
                    source(sum.right(), other.right(), depth + 1);
                }
                case Product product -> {
                    if (!(recorded instanceof Product other)) throw new Rejected(INVALID_CERTIFICATE, "SOURCE_PRODUCT");
                    source(product.left(), other.left(), depth + 1);
                    source(product.right(), other.right(), depth + 1);
                }
                case Inverse inverse -> {
                    if (!(recorded instanceof Inverse other)) throw new Rejected(INVALID_CERTIFICATE, "SOURCE_INVERSE");
                    source(inverse.operand(), other.operand(), depth + 1);
                }
            }
        }

        void root(RationalFormula source) {
            int order = certificate.pencil().order();
            work.ledger.cells((long) order * order + certificate.u().size() + certificate.v().size()
                + certificate.obligations().size());
            expect(certificate.u().size() == order && certificate.v().size() == order, "SELECTOR_LENGTH");
            Masks masks = node(source, 0, 1);
            expect(masks.order() == order, "PENCIL_ORDER");
            expect(obligationIndex == certificate.obligations().size(), "EXTRA_INVERSE_OBLIGATION");
            for (int i = 0; i < order; i++) {
                expect(work.ledger.check(certificate.u().get(i)).equals(unit(masks.u()[i])), "ROW_SELECTOR");
                expect(work.ledger.check(certificate.v().get(i)).equals(unit(masks.v()[i])), "COLUMN_SELECTOR");
            }
        }

        private Masks node(RationalFormula source, int offset, int depth) {
            work.enter(depth);
            Masks masks = switch (source) {
                case Scalar scalar -> {
                    work.ledger.check(scalar.value());
                    Masks leaf = leaf(offset);
                    constant(offset, offset + 1, scalar.value().negate());
                    yield leaf;
                }
                case Variable variable -> {
                    Masks leaf = leaf(offset);
                    var entry = certificate.pencil().entry(offset, offset + 1);
                    expect(entry instanceof VariableTerm term && term.variableId() == variable.id()
                        && work.ledger.check(term.coefficient()).equals(NEGATIVE_ONE), "LEAF_VARIABLE");
                    yield leaf;
                }
                case Sum sum -> binary(sum.left(), sum.right(), offset, depth, false);
                case Product product -> binary(product.left(), product.right(), offset, depth, true);
                case Inverse inverse -> inverse(inverse.operand(), offset, depth);
            };
            nodeIndex++;
            return masks;
        }

        private Masks leaf(int offset) {
            expect(offset + 2 <= certificate.pencil().order(), "LEAF_ORDER");
            constant(offset, offset, ONE);
            constant(offset + 1, offset, ZERO);
            constant(offset + 1, offset + 1, ONE);
            work.reserve(4);
            return new Masks(new byte[]{1, 0}, new byte[]{0, 1});
        }

        private Masks binary(RationalFormula left, RationalFormula right, int offset, int depth, boolean product) {
            Masks a = node(left, offset, depth + 1);
            int split = offset + a.order();
            Masks b = node(right, split, depth + 1);
            for (int i = 0; i < a.order(); i++) {
                for (int j = 0; j < b.order(); j++) {
                    constant(split + j, offset + i, ZERO);
                    constant(offset + i, split + j, product ? unit(-a.v()[i] * b.u()[j]) : ZERO);
                }
            }
            int order = a.order() + b.order();
            work.reserve(2L * order);
            byte[] u = new byte[order];
            byte[] v = new byte[order];
            for (int i = 0; i < order; i++) {
                work.ledger.charge(1);
                if (i < a.order()) {
                    u[i] = a.u()[i];
                    v[i] = product ? 0 : a.v()[i];
                } else {
                    u[i] = product ? 0 : b.u()[i - a.order()];
                    v[i] = b.v()[i - a.order()];
                }
            }
            return new Masks(u, v);
        }

        private Masks inverse(RationalFormula operand, int offset, int depth) {
            Masks child = node(operand, offset, depth + 1);
            int order = child.order() + 1;
            int border = offset + order - 1;
            expect(border < certificate.pencil().order(), "INVERSE_ORDER");
            for (int i = 0; i < child.order(); i++) {
                constant(offset + i, border, unit(child.v()[i]));
                constant(border, offset + i, unit(child.u()[i]));
            }
            constant(border, border, ZERO);
            expect(obligationIndex < certificate.obligations().size(), "MISSING_INVERSE_OBLIGATION");
            var obligation = certificate.obligations().get(obligationIndex++);
            expect(obligation.occurrence() == nodeIndex && obligation.offset() == offset && obligation.order() == order,
                "INVERSE_OCCURRENCE");
            work.reserve(2L * order);
            byte[] u = new byte[order];
            byte[] v = new byte[order];
            u[order - 1] = -1;
            v[order - 1] = 1;
            return new Masks(u, v);
        }

        private void constant(int row, int column, ExactRational value) {
            work.ledger.charge(1);
            var entry = certificate.pencil().entry(row, column);
            expect(entry instanceof Constant scalar && work.ledger.check(scalar.value()).equals(value), "PENCIL_ENTRY");
        }

        private static ExactRational unit(int sign) {
            if (sign == 0) return ZERO;
            return sign > 0 ? ONE : NEGATIVE_ONE;
        }
    }
}
