package de.regelsuche.math.algorithms.noncommutative;

import de.regelsuche.math.algorithms.certificates.CertificateWork;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Inverse;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Product;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Scalar;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Sum;
import de.regelsuche.math.algorithms.noncommutative.RationalFormula.Variable;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static de.regelsuche.scalar.ExactRational.NEGATIVE_ONE;
import static de.regelsuche.scalar.ExactRational.ONE;
import static de.regelsuche.scalar.ExactRational.ZERO;

/** The family-116 block realization h = u L^-1 v, retaining the source tree's inverse gates. */
public final class RationalFormulaRealization {
    private RationalFormulaRealization() {}

    public enum Status {
        REALIZED, VERIFIED, DEFINED, UNDEFINED, INVALID_CERTIFICATE, INVALID_ASSIGNMENT, RESOURCE_LIMIT, CANCELLED
    }

    /** Each entry in this block representation is a rational constant or a single linear term. */
    public sealed interface PencilEntry permits Constant, VariableTerm {}

    public record Constant(ExactRational value) implements PencilEntry {
        public Constant { Objects.requireNonNull(value, "value"); }
    }

    public record VariableTerm(int variableId, ExactRational coefficient) implements PencilEntry {
        public VariableTerm { Objects.requireNonNull(coefficient, "coefficient"); }
    }

    /** Square affine pencil in row-major order. */
    public record AffinePencil(int order, List<PencilEntry> entries) {
        public AffinePencil {
            entries = List.copyOf(entries);
            if (order < 1 || (long) order * order != entries.size()) {
                throw new IllegalArgumentException("INVALID_PENCIL_ORDER");
            }
        }

        public PencilEntry entry(int row, int column) {
            return entries.get(Objects.checkIndex(row, order) * order + Objects.checkIndex(column, order));
        }
    }

    /** Zero-based postorder gate index and the principal subpencil to be inverted at that gate. */
    public record InverseObligation(int occurrence, int offset, int order) {}

    public record Certificate(RationalFormula source, AffinePencil pencil, List<ExactRational> u,
                              List<ExactRational> v, List<InverseObligation> obligations) {
        public Certificate {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(pencil, "pencil");
            u = List.copyOf(u);
            v = List.copyOf(v);
            obligations = List.copyOf(obligations);
        }
    }

    public record Result(Status status, Optional<Certificate> certificate, String reason, long work) {}

    public static Result realize(RationalFormula source, IterationLimits limits, BooleanSupplier cancelled) {
        Objects.requireNonNull(source, "source");
        var work = new RationalFormulaWork(limits, cancelled);
        try {
            var certificate = build(source, work);
            work.ledger.charge(0);
            return new Result(Status.REALIZED, Optional.of(certificate), "", work.ledger.used());
        } catch (CertificateWork.Stop stopped) {
            return new Result(RationalFormulaWork.status(stopped), Optional.empty(), stopped.getMessage(),
                work.ledger.used());
        } catch (RationalFormulaVerifier.Rejected rejected) {
            return new Result(rejected.status, Optional.empty(), rejected.getMessage(), work.ledger.used());
        }
    }

    static Certificate build(RationalFormula source, RationalFormulaWork work) {
        int order = order(source, 1, work);
        var builder = new Builder(order, work);
        Selectors selectors = builder.write(source, 0);
        var certificate = new Certificate(source, new AffinePencil(order, Arrays.asList(builder.entries)),
            Arrays.asList(selectors.u()), Arrays.asList(selectors.v()), builder.obligations);
        RationalFormulaVerifier.checkStructure(source, certificate, work);
        return certificate;
    }

    private static int order(RationalFormula source, int depth, RationalFormulaWork work) {
        work.enter(depth);
        int order = switch (source) {
            case Scalar scalar -> {
                work.ledger.check(scalar.value());
                yield 2;
            }
            case Variable ignored -> 2;
            case Sum sum -> order(sum.left(), depth + 1, work) + order(sum.right(), depth + 1, work);
            case Product product -> order(product.left(), depth + 1, work) + order(product.right(), depth + 1, work);
            case Inverse inverse -> order(inverse.operand(), depth + 1, work) + 1;
        };
        work.ledger.cells((long) order * order);
        return order;
    }

    private record Selectors(ExactRational[] u, ExactRational[] v) {
        int order() { return u.length; }
    }

    private static final class Builder {
        private final int order;
        private final RationalFormulaWork work;
        private final PencilEntry[] entries;
        private final List<InverseObligation> obligations = new ArrayList<>();
        private int occurrence;

        Builder(int order, RationalFormulaWork work) {
            this.order = order;
            this.work = work;
            // Count both the mutable builder storage and the immutable certificate snapshot.
            work.reserve(2L * order * order + 2L * order);
            entries = new PencilEntry[order * order];
            Arrays.fill(entries, new Constant(ZERO));
        }

        Selectors write(RationalFormula source, int offset) {
            work.ledger.charge(1);
            Selectors selectors = switch (source) {
                case Scalar scalar -> leaf(offset, new Constant(scalar.value().negate()));
                case Variable variable -> leaf(offset, new VariableTerm(variable.id(), NEGATIVE_ONE));
                case Sum sum -> binary(sum.left(), sum.right(), offset, false);
                case Product product -> binary(product.left(), product.right(), offset, true);
                case Inverse inverse -> inverse(inverse.operand(), offset);
            };
            occurrence++;
            return selectors;
        }

        private Selectors leaf(int offset, PencilEntry value) {
            put(offset, offset, new Constant(ONE));
            put(offset, offset + 1, value);
            put(offset + 1, offset + 1, new Constant(ONE));
            Selectors selectors = zeros(2);
            selectors.u()[0] = ONE;
            selectors.v()[1] = ONE;
            return selectors;
        }

        private Selectors binary(RationalFormula left, RationalFormula right, int offset, boolean product) {
            Selectors a = write(left, offset);
            int rightOffset = offset + a.order();
            Selectors b = write(right, rightOffset);
            Selectors combined = zeros(a.order() + b.order());
            System.arraycopy(a.u(), 0, combined.u(), 0, a.order());
            System.arraycopy(b.v(), 0, combined.v(), a.order(), b.order());
            if (product) {
                for (int row = 0; row < a.order(); row++) {
                    for (int column = 0; column < b.order(); column++) {
                        ExactRational cross = work.ledger.mul(a.v()[row], b.u()[column]).negate();
                        put(offset + row, rightOffset + column, new Constant(cross));
                    }
                }
            } else {
                System.arraycopy(b.u(), 0, combined.u(), a.order(), b.order());
                System.arraycopy(a.v(), 0, combined.v(), 0, a.order());
            }
            return combined;
        }

        private Selectors inverse(RationalFormula operand, int offset) {
            Selectors child = write(operand, offset);
            int border = offset + child.order();
            for (int i = 0; i < child.order(); i++) {
                put(offset + i, border, new Constant(child.v()[i]));
                put(border, offset + i, new Constant(child.u()[i]));
            }
            Selectors result = zeros(child.order() + 1);
            result.u()[child.order()] = NEGATIVE_ONE;
            result.v()[child.order()] = ONE;
            work.reserve(2);
            obligations.add(new InverseObligation(occurrence, offset, result.order()));
            return result;
        }

        private Selectors zeros(int size) {
            work.reserve(2L * size);
            var u = new ExactRational[size];
            var v = new ExactRational[size];
            Arrays.fill(u, ZERO);
            Arrays.fill(v, ZERO);
            return new Selectors(u, v);
        }

        private void put(int row, int column, PencilEntry entry) {
            work.ledger.charge(1);
            entries[row * order + column] = entry;
        }
    }
}
