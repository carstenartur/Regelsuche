package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import static de.regelsuche.math.algorithms.algebra.OperatorResidualPlans.*;
import static de.regelsuche.math.algorithms.algebra.OperatorChecks.need;

/** A reusable exact operator produced only after its source identity has been verified. */
public final class PreparedOperator {
    private final Plan plan;
    private final OperatorResidualSearch.Predictor predictor;
    private final int residualRank;
    private final Node program;
    private final Cost directCost;

    private PreparedOperator(Plan plan, OperatorResidualSearch.Predictor predictor, int residualRank,
                             Node program, Cost directCost) {
        this.plan = plan;
        this.predictor = predictor;
        this.residualRank = residualRank;
        this.program = program;
        this.directCost = directCost;
    }

    /** The only construction path checks the complete source identity before compiling execution. */
    static PreparedOperator create(PolynomialMatrix source, Plan plan, OperatorResidualSearch.Predictor predictor,
                                    int rank, Cost directCost, OperatorChecks checks) {
        OperatorResidualPlans.verify(source, plan, checks);
        Node program = compile(plan.expression(), checks);
        return new PreparedOperator(plan, predictor, rank, program,
            directCost == null ? sparse(source, checks).cost() : directCost);
    }

    public OperatorResidualSearch.Predictor predictor() { return predictor; }
    /** Correction width; equals residual rank for discovered factors, is only an upper bound for supplied ones. */
    public int correctionWidth() { return residualRank; }
    public Plan plan() { return plan; }

    /** Counts nonunit scalar products and staged additions; zero entries are omitted. */
    public Cost cost() { return program.cost(); }
    public Cost directCost() { return directCost; }

    /** Replay a previously checked immutable program; acquisition is not repeated for each vector. */
    public Result<List<ExactRational>> apply(List<ExactRational> vector, IterationLimits limits,
                                           BooleanSupplier cancelled) {
        return OperatorChecks.run(limits, cancelled, checks -> {
            need(vector != null && vector.size() == program.columns(), "VECTOR_DIMENSION_MISMATCH");
            checks.reserve(vector.size(), 1);
            for (var value : vector) {
                need(value != null, "MISSING_VECTOR_VALUE");
                checks.work.check(value);
            }
            var input = vector.toArray(ExactRational[]::new);
            var output = program.apply(input, checks);
            checks.reserve(output.length, 1);
            return List.of(output);
        });
    }

    private sealed interface Node permits Sparse, Unit, Composition, Addition {
        int rows();
        int columns();
        Cost cost();
        ExactRational[] apply(ExactRational[] input, OperatorChecks checks);
    }

    private record Entry(int column, ExactRational coefficient) {}

    private record Sparse(int rows, int columns, List<List<Entry>> entries, Cost cost, boolean zero) implements Node {
        @Override public ExactRational[] apply(ExactRational[] input, OperatorChecks checks) {
            checks.reserve(rows, 1);
            var output = new ExactRational[rows];
            for (int row = 0; row < rows; row++) {
                checks.work.charge(1);
                var sum = ExactRational.ZERO;
                boolean first = true;
                for (var entry : entries.get(row)) {
                    checks.work.charge(1);
                    var term = entry.coefficient().equals(ExactRational.ONE) ? input[entry.column()]
                        : checks.work.mul(entry.coefficient(), input[entry.column()]);
                    sum = first ? term : checks.work.add(sum, term);
                    first = false;
                }
                output[row] = sum;
            }
            return output;
        }
    }

    private record Unit(int rows) implements Node {
        @Override public int columns() { return rows; }
        @Override public Cost cost() { return new Cost(0, 0); }
        @Override public ExactRational[] apply(ExactRational[] input, OperatorChecks checks) {
            checks.work.charge(1);
            return input;
        }
    }

    private record Composition(Node left, Node right) implements Node {
        @Override public int rows() { return left.rows(); }
        @Override public int columns() { return right.columns(); }
        @Override public Cost cost() { return left.cost().plus(right.cost()); }
        @Override public ExactRational[] apply(ExactRational[] input, OperatorChecks checks) {
            checks.work.charge(1);
            return left.apply(right.apply(input, checks), checks);
        }
    }

    private record Addition(Node left, Node right) implements Node {
        @Override public int rows() { return left.rows(); }
        @Override public int columns() { return left.columns(); }
        @Override public Cost cost() { return left.cost().plus(right.cost()).updates(rows()); }
        @Override public ExactRational[] apply(ExactRational[] input, OperatorChecks checks) {
            checks.work.charge(1);
            var a = left.apply(input, checks);
            var b = right.apply(input, checks);
            checks.reserve(rows(), 1);
            var output = new ExactRational[rows()];
            for (int row = 0; row < rows(); row++) output[row] = checks.work.add(a[row], b[row]);
            return output;
        }
    }

    private static Node compile(ExactMatrixExpression expression, OperatorChecks checks) {
        checks.work.charge(1);
        checks.reserve(1, 1);
        return switch (expression) {
            case ExactMatrixExpression.Matrix matrix -> sparse(matrix.value(), checks);
            case ExactMatrixExpression.Identity identity -> new Unit(identity.dimension());
            case ExactMatrixExpression.Product product ->
                new Composition(compile(product.left(), checks), compile(product.right(), checks));
            case ExactMatrixExpression.Sum sum -> {
                var a = compile(sum.left(), checks);
                var b = compile(sum.right(), checks);
                if (a instanceof Sparse sparse && sparse.zero()) yield b;
                if (b instanceof Sparse sparse && sparse.zero()) yield a;
                yield new Addition(a, b);
            }
            default -> throw new OperatorChecks.Rejected(Status.UNSUPPORTED, "UNSUPPORTED_OPERATOR_SYNTAX");
        };
    }

    private static Sparse sparse(PolynomialMatrix matrix, OperatorChecks checks) {
        checks.reserve(matrix.rows(), 1);
        // Mutable lists, immutable snapshots and each (column, rational) entry.
        for (int part = 0; part < 4; part++) checks.reserve(matrix.rows(), matrix.columns());
        var rows = new ArrayList<List<Entry>>(matrix.rows());
        long multiplies = 0;
        long additions = 0;
        boolean zero = true;
        for (int row = 0; row < matrix.rows(); row++) {
            var entries = new ArrayList<Entry>();
            for (int column = 0; column < matrix.columns(); column++) {
                checks.work.charge(1);
                var value = matrix.get(row, column);
                if (value.isZero()) continue;
                var rational = value.terms().values().iterator().next();
                var coefficient = checks.work.check(new ExactRational(rational.numerator(), rational.denominator()));
                entries.add(new Entry(column, coefficient));
                if (!coefficient.equals(ExactRational.ONE)) multiplies++;
                zero = false;
            }
            additions += Math.max(0, entries.size() - 1);
            rows.add(List.copyOf(entries));
        }
        return new Sparse(matrix.rows(), matrix.columns(), List.copyOf(rows), new Cost(multiplies, additions), zero);
    }
}
