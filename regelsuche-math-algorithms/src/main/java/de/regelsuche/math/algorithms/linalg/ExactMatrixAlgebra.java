package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.representation.RepresentationBridge;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/** Bounded polynomial matrix arithmetic and a separate staged vector replay. */
public final class ExactMatrixAlgebra {
    public static final int MAX_DIMENSION = 16;
    public static final int MAX_TERMS = 256;
    public static final int MAX_DEGREE = 32;
    public static final int MAX_COEFFICIENT_BITS = 2048;
    public static final int MAX_DEPTH = 32;
    private final Work work;
    private final Map<ExactMatrixExpression, PolynomialMatrix> values = new IdentityHashMap<>();

    public ExactMatrixAlgebra(Work work) {
        this.work = java.util.Objects.requireNonNull(work, "work");
    }

    public PolynomialMatrix evaluate(ExactMatrixExpression expression) {
        return evaluate(expression, 0);
    }

    private PolynomialMatrix evaluate(ExactMatrixExpression expression, int depth) {
        enter(depth);
        PolynomialMatrix cached = values.get(expression);
        if (cached != null) {
            return cached;
        }
        PolynomialMatrix value = switch (expression) {
            case ExactMatrixExpression.Matrix m -> checked(m.value());
            case ExactMatrixExpression.Identity i -> identity(i.dimension());
            case ExactMatrixExpression.Product p -> multiply(
                evaluate(p.left(), depth + 1), evaluate(p.right(), depth + 1));
            case ExactMatrixExpression.Sum s -> add(
                evaluate(s.left(), depth + 1), evaluate(s.right(), depth + 1));
            case ExactMatrixExpression.Inverse i -> inverse(i, depth);
            case ExactMatrixExpression.BlockDiagonal b -> blocks(b, depth);
            case ExactMatrixExpression.Mapped m -> restore(m, depth);
        };
        values.put(expression, value);
        return value;
    }

    private PolynomialMatrix inverse(ExactMatrixExpression.Inverse inverse, int depth) {
        PolynomialMatrix matrix = evaluate(inverse.operand(), depth + 1);
        PolynomialMatrix witness = checked(inverse.witness());
        require(matrix.rows() == matrix.columns(), "INVERSE_REQUIRES_SQUARE_MATRIX");
        PolynomialMatrix unit = identity(matrix.rows());
        require(equal(multiply(matrix, witness), unit)
            && equal(multiply(witness, matrix), unit), "INVERSE_WITNESS_REJECTED");
        return witness;
    }

    private PolynomialMatrix blocks(ExactMatrixExpression.BlockDiagonal expression, int depth) {
        List<PolynomialMatrix> blocks = expression.blocks().stream()
            .map(block -> evaluate(block, depth + 1)).toList();
        int rows = blocks.stream().mapToInt(PolynomialMatrix::rows).sum();
        int columns = blocks.stream().mapToInt(PolynomialMatrix::columns).sum();
        List<List<Polynomial>> result = mutableZeros(rows, columns);
        int rowOffset = 0;
        int columnOffset = 0;
        for (PolynomialMatrix block : blocks) {
            for (int row = 0; row < block.rows(); row++) {
                for (int column = 0; column < block.columns(); column++) {
                    work.consume(1);
                    result.get(rowOffset + row).set(columnOffset + column, block.get(row, column));
                }
            }
            rowOffset += block.rows();
            columnOffset += block.columns();
        }
        return new PolynomialMatrix(result);
    }

    private PolynomialMatrix restore(ExactMatrixExpression.Mapped mapping, int depth) {
        PolynomialMatrix source = evaluate(mapping.expression(), depth + 1);
        require(source.rows() == mapping.rows().size()
            && source.columns() == mapping.columns().size(), "MAPPING_DIMENSION_MISMATCH");
        List<List<Polynomial>> result = mutableZeros(source.rows(), source.columns());
        for (int row = 0; row < source.rows(); row++) {
            for (int column = 0; column < source.columns(); column++) {
                work.consume(1);
                result.get(mapping.rows().get(row)).set(mapping.columns().get(column), source.get(row, column));
            }
        }
        return new PolynomialMatrix(result);
    }

    public PolynomialMatrix multiply(PolynomialMatrix left, PolynomialMatrix right) {
        require(left.columns() == right.rows(), "PRODUCT_DIMENSION_MISMATCH");
        return matrix(left.rows(), right.columns(), (row, column) -> {
            Polynomial sum = Polynomial.zero();
            for (int inner = 0; inner < left.columns(); inner++) {
                sum = add(sum, multiply(left.get(row, inner), right.get(inner, column)));
            }
            return sum;
        });
    }

    public PolynomialMatrix add(PolynomialMatrix left, PolynomialMatrix right) {
        require(left.rows() == right.rows() && left.columns() == right.columns(),
            "SUM_DIMENSION_MISMATCH");
        return matrix(left.rows(), left.columns(), (r, c) -> add(left.get(r, c), right.get(r, c)));
    }

    public boolean equal(PolynomialMatrix left, PolynomialMatrix right) {
        if (left.rows() != right.rows() || left.columns() != right.columns()) {
            return false;
        }
        boolean equal = true;
        for (int row = 0; row < left.rows(); row++) {
            for (int column = 0; column < left.columns(); column++) {
                work.consume(1);
                equal &= left.get(row, column).equals(right.get(row, column));
            }
        }
        return equal;
    }

    public PolynomialMatrix identity(int dimension) {
        return matrix(dimension, dimension, (r, c) -> Polynomial.constant(
            r.equals(c) ? Rational.ONE : Rational.ZERO));
    }

    public PolynomialMatrix matrix(int rows, int columns, BiFunction<Integer, Integer, Polynomial> entry) {
        dimensions(rows, columns);
        List<List<Polynomial>> result = new ArrayList<>();
        for (int row = 0; row < rows; row++) {
            List<Polynomial> entries = new ArrayList<>();
            for (int column = 0; column < columns; column++) {
                work.consume(1);
                entries.add(checked(entry.apply(row, column)));
            }
            result.add(entries);
        }
        return new PolynomialMatrix(result);
    }

    private List<List<Polynomial>> mutableZeros(int rows, int columns) {
        return matrix(rows, columns, (r, c) -> Polynomial.zero()).entries().stream()
            .map(row -> (List<Polynomial>) new ArrayList<>(row)).toList();
    }

    public PolynomialMatrix checked(PolynomialMatrix matrix) {
        return matrix(matrix.rows(), matrix.columns(), matrix::get);
    }

    /** Replays composition right-to-left on a vector, independently of matrix expansion. */
    public List<Polynomial> apply(ExactMatrixExpression expression, List<Polynomial> vector) {
        return apply(expression, vector, 0);
    }

    private List<Polynomial> apply(ExactMatrixExpression expression, List<Polynomial> vector, int depth) {
        enter(depth);
        return switch (expression) {
            case ExactMatrixExpression.Product p -> apply(p.left(), apply(p.right(), vector, depth + 1), depth + 1);
            case ExactMatrixExpression.Sum s -> addVectors(
                apply(s.left(), vector, depth + 1), apply(s.right(), vector, depth + 1));
            case ExactMatrixExpression.Identity i -> {
                require(i.dimension() == vector.size(), "VECTOR_DIMENSION_MISMATCH");
                yield List.copyOf(vector);
            }
            default -> apply(evaluate(expression, depth + 1), vector);
        };
    }

    public List<Polynomial> apply(PolynomialMatrix matrix, List<Polynomial> vector) {
        require(matrix.columns() == vector.size(), "VECTOR_DIMENSION_MISMATCH");
        List<Polynomial> result = new ArrayList<>();
        for (int row = 0; row < matrix.rows(); row++) {
            Polynomial value = Polynomial.zero();
            for (int column = 0; column < matrix.columns(); column++) {
                value = add(value, multiply(matrix.get(row, column), vector.get(column)));
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private List<Polynomial> addVectors(List<Polynomial> left, List<Polynomial> right) {
        require(left.size() == right.size(), "VECTOR_DIMENSION_MISMATCH");
        List<Polynomial> result = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            result.add(add(left.get(i), right.get(i)));
        }
        return List.copyOf(result);
    }

    public Polynomial scalar(Expr expression) {
        return scalar(expression, 0);
    }

    private Polynomial scalar(Expr expression, int depth) {
        enter(depth);
        return switch (expression) {
            case NumberExpr n -> checked(Polynomial.constant(Rational.fromExact(n.value())));
            case VariableExpr v -> Polynomial.variable(v.name());
            case BinaryExpr b -> binaryScalar(b, depth);
            default -> throw new Unsupported("NON_POLYNOMIAL_SCALAR");
        };
    }

    private Polynomial binaryScalar(BinaryExpr expression, int depth) {
        Polynomial left = scalar(expression.left(), depth + 1);
        Polynomial right = scalar(expression.right(), depth + 1);
        return switch (expression.operator()) {
            case ADD -> add(left, right);
            case SUB -> add(left, right.multiply(Rational.NEGATIVE_ONE));
            case MUL -> multiply(left, right);
            case DIV -> {
                require(right.variables().isEmpty() && !right.isZero(), "NON_CONSTANT_OR_ZERO_DENOMINATOR");
                yield checked(left.multiply(Rational.ONE.divide(right.terms().values().iterator().next())));
            }
            case POW -> power(left, right);
        };
    }

    private Polynomial power(Polynomial base, Polynomial exponent) {
        require(exponent.variables().isEmpty(), "NON_CONSTANT_EXPONENT");
        Rational value = exponent.isZero() ? Rational.ZERO : exponent.terms().values().iterator().next();
        require(value.denominator().equals(java.math.BigInteger.ONE)
            && value.numerator().signum() >= 0
            && value.numerator().compareTo(java.math.BigInteger.valueOf(20)) <= 0, "EXPONENT_OUTSIDE_FRAGMENT");
        require(!base.isZero() || !value.isZero(), "ZERO_TO_ZERO_POWER");
        require(!value.isZero() || base.variables().isEmpty(), "ZERO_EXPONENT_REQUIRES_NONZERO_BASE");
        Polynomial result = Polynomial.constant(Rational.ONE);
        for (int i = 0; i < value.numerator().intValueExact(); i++) {
            result = multiply(result, base);
        }
        return result;
    }

    public Polynomial add(Polynomial left, Polynomial right) {
        work.consume(Math.max(1L, (long) left.termCount() + right.termCount()));
        return checked(left.add(right));
    }

    public Polynomial multiply(Polynomial left, Polynomial right) {
        work.consume(Math.max(1L, (long) left.termCount() * right.termCount()));
        require((long) left.totalDegree() + right.totalDegree() <= MAX_DEGREE,
            "POLYNOMIAL_DEGREE_LIMIT");
        return checked(left.multiply(right));
    }

    private Polynomial checked(Polynomial value) {
        require(value.termCount() <= MAX_TERMS,
            "POLYNOMIAL_SIZE_LIMIT");
        for (var monomial : value.terms().keySet()) {
            work.consume(1);
            long degree = monomial.powers().values().stream().mapToLong(Integer::longValue).sum();
            require(degree <= MAX_DEGREE, "POLYNOMIAL_DEGREE_LIMIT");
        }
        for (Rational coefficient : value.terms().values()) {
            work.consume(1);
            require(coefficient.numerator().bitLength() <= MAX_COEFFICIENT_BITS
                && coefficient.denominator().bitLength() <= MAX_COEFFICIENT_BITS,
                "COEFFICIENT_SIZE_LIMIT");
        }
        return value;
    }

    private void enter(int depth) {
        work.consume(1);
        require(depth <= MAX_DEPTH, "EXPRESSION_DEPTH_LIMIT");
    }

    private static void dimensions(int rows, int columns) {
        require(rows > 0 && columns > 0 && rows <= MAX_DIMENSION && columns <= MAX_DIMENSION,
            "MATRIX_DIMENSION_LIMIT");
    }

    static void require(boolean valid, String reason) {
        if (!valid) {
            throw new Unsupported(reason);
        }
    }

    public static final class Work {
        private final int configured;
        private int consumed;

        public Work(int configured) {
            if (configured < 0) {
                throw new IllegalArgumentException("work must not be negative");
            }
            this.configured = configured;
        }

        public void consume(long units) {
            if (units < 0) {
                throw new IllegalArgumentException("negative work");
            }
            if (units > remaining()) {
                throw new Exhausted();
            }
            consumed += (int) units;
        }

        public int remaining() {
            return configured - consumed;
        }

        public RepresentationBridge.WorkLedger ledger() {
            return RepresentationBridge.WorkLedger.of(configured, consumed);
        }
    }

    public static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    public static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Unsupported(String detail) {
            super(detail);
        }
    }
}
