package de.regelsuche.math.algorithms.algebra;

import de.regelsuche.math.algorithms.iteration.IterationLimits;
import de.regelsuche.math.algorithms.linalg.ExactMatrixExpression;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.scalar.ExactRational;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
        if (directCost == null) {
            directCost = predictor == OperatorResidualSearch.Predictor.DIRECT ? program.cost() : sparse(source, checks).cost();
        }
        return new PreparedOperator(plan, predictor, rank, program, directCost);
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

    sealed interface Node permits Sparse, Unit, Composition, Addition {
        int rows();
        int columns();
        Cost cost();
        ExactRational[] apply(ExactRational[] input, OperatorChecks checks);
    }

    private record Scaling(int column, ExactRational coefficient) {}

    /** Assigns one register per distinct scaling, and a value slot only on its second use. */
    private static final class ScalingRegisters {
        final LinkedHashMap<Scaling, Integer> indices = new LinkedHashMap<>();
        final int[] slots;
        int sharedCount;

        ScalingRegisters(int maximumScalings) {
            slots = new int[maximumScalings];
            Arrays.fill(slots, -1);
        }

        int register(Scaling scaling) {
            int next = indices.size();
            Integer existing = indices.putIfAbsent(scaling, next);
            if (existing == null) return next;
            if (slots[existing] < 0) slots[existing] = sharedCount++;
            return existing;
        }
    }

    /** Registers below columns refer to inputs; higher registers refer to exact scaled inputs. */
    private record Sparse(int columns, int[][] rowRegisters, List<Scaling> scalings, int[] slots,
                          int sharedCount, Cost cost, boolean zero) implements Node {
        @Override public int rows() { return rowRegisters.length; }

        @Override public ExactRational[] apply(ExactRational[] input, OperatorChecks checks) {
            checks.reserveCells(sharedCount);
            var shared = new ExactRational[sharedCount];
            checks.reserve(rows(), 1);
            var output = new ExactRational[rows()];
            for (int row = 0; row < rows(); row++) {
                checks.work.charge(1);
                ExactRational sum = null;
                for (int register : rowRegisters[row]) {
                    checks.work.charge(1);
                    var term = value(register, input, shared, checks);
                    sum = sum == null ? term : checks.work.add(sum, term);
                }
                output[row] = sum == null ? ExactRational.ZERO : sum;
            }
            return output;
        }

        private ExactRational value(int register, ExactRational[] input, ExactRational[] shared, OperatorChecks checks) {
            if (register < columns) return input[register];
            int index = register - columns;
            int slot = slots[index];
            if (slot >= 0 && shared[slot] != null) return shared[slot];
            var scaling = scalings.get(index);
            var product = checks.work.mul(scaling.coefficient(), input[scaling.column()]);
            if (slot >= 0) shared[slot] = product;
            return product;
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

    static Node compile(ExactMatrixExpression expression, OperatorChecks checks) {
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
        checks.reserve(1, matrix.columns());
        // Row registers, scaling descriptors, insertion-ordered lookup and immutable scaling table.
        checks.reserveCells(8L * matrix.rows() * matrix.columns());
        var rows = new int[matrix.rows()][];
        var rowBuffer = new int[matrix.columns()];
        var scalings = new ScalingRegisters(matrix.rows() * matrix.columns());
        long additions = 0;
        boolean zero = true;
        for (int row = 0; row < matrix.rows(); row++) {
            int used = 0;
            for (int column = 0; column < matrix.columns(); column++) {
                checks.work.charge(1);
                var value = matrix.get(row, column);
                if (value.isZero()) continue;
                var rational = value.terms().values().iterator().next();
                var coefficient = checks.work.check(new ExactRational(rational.numerator(), rational.denominator()));
                int register = column;
                if (!coefficient.isOne()) {
                    register = matrix.columns() + scalings.register(new Scaling(column, coefficient));
                }
                rowBuffer[used++] = register;
            }
            additions += Math.max(0, used - 1);
            rows[row] = Arrays.copyOf(rowBuffer, used);
            if (used != 0) zero = false;
        }
        int products = scalings.indices.size();
        return new Sparse(matrix.columns(), rows, List.copyOf(scalings.indices.keySet()),
            Arrays.copyOf(scalings.slots, products), scalings.sharedCount, new Cost(products, additions), zero);
    }
}
