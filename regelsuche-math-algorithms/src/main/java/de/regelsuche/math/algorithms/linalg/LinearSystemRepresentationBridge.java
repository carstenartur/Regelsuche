package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactMatrix;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.RowOrigin;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.representation.RepresentationBridge;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Exact, bounded bridge from scalar equations to {@code A*x=b}.
 *
 * <p>Every equation is analyzed as an affine rational form. The bridge keeps a
 * deterministic variable order, exact coefficient/RHS values and source-row
 * provenance. It reconstructs every normalized scalar row before emitting a
 * result and independently recomputes the complete result in {@link #verify}.</p>
 */
public final class LinearSystemRepresentationBridge implements
        RepresentationBridge<List<Equation>, ExactLinearSystem,
            LinearSystemRepresentationBridge.Certificate> {

    public static final String BRIDGE_ID =
        "equation-system-to-exact-matrix/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.linear-system-representation-certificate/v1";
    private static final Relation RELATION =
        Relation.SOLUTION_SET_EQUIVALENCE;
    private static final int MAX_ABSOLUTE_CONSTANT_EXPONENT = 64;

    @Override
    public Result<ExactLinearSystem, Certificate> analyze(
        List<Equation> source,
        Budget budget
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        List<Equation> equations = List.copyOf(source);
        WorkCounter work = new WorkCounter(budget.maxWorkUnits());
        if (equations.isEmpty()) {
            return withoutRepresentation(
                Status.NOT_APPLICABLE,
                work,
                "EMPTY_EQUATION_SYSTEM");
        }

        try {
            Set<String> variables = collectVariables(equations, work);
            if (variables.isEmpty()) {
                return withoutRepresentation(
                    Status.NOT_APPLICABLE,
                    work,
                    "SYSTEM_CONTAINS_NO_VARIABLES");
            }

            List<AffineForm> normalizedRows = normalize(equations, work);
            List<String> variableOrder = List.copyOf(variables);
            List<List<Rational>> coefficientRows = new ArrayList<>(
                normalizedRows.size());
            List<Rational> rightHandSide = new ArrayList<>(
                normalizedRows.size());
            List<RowOrigin> rowOrigins = new ArrayList<>(
                normalizedRows.size());
            for (int rowIndex = 0;
                    rowIndex < normalizedRows.size();
                    rowIndex++) {
                AffineForm normalized = normalizedRows.get(rowIndex);
                List<Rational> row = new ArrayList<>(variableOrder.size());
                for (String variable : variableOrder) {
                    work.consume();
                    row.add(normalized.coefficient(variable));
                }
                coefficientRows.add(List.copyOf(row));
                rightHandSide.add(normalized.constant().negate());
                rowOrigins.add(new RowOrigin(
                    rowIndex,
                    formatEquation(equations.get(rowIndex))));
            }

            ExactMatrix coefficients = new ExactMatrix(coefficientRows);
            ExactVector rhs = new ExactVector(rightHandSide);
            int coefficientRank = rank(coefficientRows, work);
            int augmentedRank = rank(
                augmentedRows(coefficientRows, rightHandSide),
                work);
            SolutionClassification classification = classify(
                coefficientRank,
                augmentedRank,
                variableOrder.size());
            ExactLinearSystem represented = new ExactLinearSystem(
                coefficients,
                variableOrder,
                rhs,
                rowOrigins,
                coefficientRank,
                augmentedRank,
                classification);

            if (!roundTripMatches(normalizedRows, represented, work)) {
                return withoutRepresentation(
                    Status.INVALID_CERTIFICATE,
                    work,
                    "SCALAR_MATRIX_ROUND_TRIP_MISMATCH");
            }

            Certificate certificate = certificate(equations, represented);
            return Result.represented(
                represented,
                certificate,
                RELATION,
                work.ledger(),
                "EXACT_LINEAR_SYSTEM_REPRESENTED");
        } catch (BudgetExceeded exception) {
            return withoutRepresentation(
                Status.BUDGET_INCONCLUSIVE,
                work,
                "REPRESENTATION_WORK_BUDGET_EXHAUSTED");
        } catch (NonlinearExpression exception) {
            return withoutRepresentation(
                Status.NONLINEAR,
                work,
                exception.detailCode());
        } catch (UnsupportedExpression exception) {
            return withoutRepresentation(
                Status.DOMAIN_UNSUPPORTED,
                work,
                exception.detailCode());
        }
    }

    @Override
    public boolean verify(
        List<Equation> source,
        Result<ExactLinearSystem, Certificate> result
    ) {
        if (source == null
                || result == null
                || result.status() != Status.REPRESENTED
                || result.relation().orElse(null) != RELATION) {
            return false;
        }
        try {
            Result<ExactLinearSystem, Certificate> recomputed = analyze(
                source,
                new Budget(result.work().configuredWorkUnits()));
            return recomputed.equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Set<String> collectVariables(
        List<Equation> equations,
        WorkCounter work
    ) {
        Set<String> result = new TreeSet<>();
        for (Equation equation : equations) {
            collectVariables(equation.left(), result, work);
            collectVariables(equation.right(), result, work);
        }
        return result;
    }

    private static void collectVariables(
        Expr expression,
        Set<String> target,
        WorkCounter work
    ) {
        work.consume();
        if (expression instanceof VariableExpr variable) {
            target.add(variable.name());
            return;
        }
        if (expression instanceof NumberExpr) {
            return;
        }
        if (expression instanceof BinaryExpr binary) {
            collectVariables(binary.left(), target, work);
            collectVariables(binary.right(), target, work);
            return;
        }
        if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) {
                collectVariables(argument, target, work);
            }
            return;
        }
        throw new UnsupportedExpression("UNKNOWN_EXPRESSION_NODE");
    }

    private static List<AffineForm> normalize(
        List<Equation> equations,
        WorkCounter work
    ) {
        List<AffineForm> result = new ArrayList<>(equations.size());
        for (Equation equation : equations) {
            AffineForm left = affine(equation.left(), work);
            AffineForm right = affine(equation.right(), work);
            result.add(left.subtract(right));
        }
        return result;
    }

    private static Result<ExactLinearSystem, Certificate>
            withoutRepresentation(
        Status status,
        WorkCounter work,
        String detailCode
    ) {
        return Result.withoutRepresentation(
            status,
            work.ledger(),
            detailCode);
    }

    private static AffineForm affine(Expr expression, WorkCounter work) {
        work.consume();
        if (expression instanceof NumberExpr number) {
            if (!Double.isFinite(number.value())) {
                throw new UnsupportedExpression("NON_FINITE_NUMBER");
            }
            return AffineForm.constant(Rational.fromDouble(number.value()));
        }
        if (expression instanceof VariableExpr variable) {
            return AffineForm.variable(variable.name());
        }
        if (expression instanceof FunctionExpr) {
            throw new UnsupportedExpression(
                "FUNCTION_OUTSIDE_EXACT_LINEAR_FRAGMENT");
        }
        if (!(expression instanceof BinaryExpr binary)) {
            throw new UnsupportedExpression("UNKNOWN_EXPRESSION_NODE");
        }

        AffineForm left = affine(binary.left(), work);
        AffineForm right = affine(binary.right(), work);
        return switch (binary.operator()) {
            case ADD -> left.add(right);
            case SUB -> left.subtract(right);
            case MUL -> multiply(left, right);
            case DIV -> divide(left, right);
            case POW -> power(left, right);
        };
    }

    private static AffineForm multiply(
        AffineForm left,
        AffineForm right
    ) {
        if (left.isConstant()) {
            return right.scale(left.constant());
        }
        if (right.isConstant()) {
            return left.scale(right.constant());
        }
        throw new NonlinearExpression("PRODUCT_OF_NON_CONSTANT_FORMS");
    }

    private static AffineForm divide(
        AffineForm numerator,
        AffineForm denominator
    ) {
        if (!denominator.isConstant()) {
            throw new NonlinearExpression("VARIABLE_IN_DENOMINATOR");
        }
        if (denominator.constant().isZero()) {
            throw new UnsupportedExpression("EXPLICIT_DIVISION_BY_ZERO");
        }
        return numerator.scale(Rational.ONE.divide(denominator.constant()));
    }

    private static AffineForm power(
        AffineForm base,
        AffineForm exponent
    ) {
        if (!exponent.isConstant()
                || !exponent.constant().denominator().equals(BigInteger.ONE)) {
            throw new NonlinearExpression(
                "NON_CONSTANT_OR_NON_INTEGER_EXPONENT");
        }
        BigInteger exactExponent = exponent.constant().numerator();
        if (exactExponent.abs().compareTo(BigInteger.valueOf(
                MAX_ABSOLUTE_CONSTANT_EXPONENT)) > 0) {
            throw new UnsupportedExpression(
                "EXPONENT_OUTSIDE_BOUNDED_EXACT_FRAGMENT");
        }
        int exponentValue = exactExponent.intValueExact();
        if (exponentValue == 1) {
            return base;
        }
        if (!base.isConstant()) {
            throw new NonlinearExpression("NONLINEAR_POWER");
        }
        if (base.constant().isZero() && exponentValue <= 0) {
            throw new UnsupportedExpression("UNDEFINED_ZERO_POWER");
        }
        return AffineForm.constant(pow(base.constant(), exponentValue));
    }

    private static Rational pow(Rational base, int exponent) {
        if (exponent == 0) {
            return Rational.ONE;
        }
        boolean reciprocal = exponent < 0;
        int remaining = Math.abs(exponent);
        Rational result = Rational.ONE;
        Rational factor = base;
        while (remaining > 0) {
            if ((remaining & 1) == 1) {
                result = result.multiply(factor);
            }
            remaining >>>= 1;
            if (remaining > 0) {
                factor = factor.multiply(factor);
            }
        }
        return reciprocal ? Rational.ONE.divide(result) : result;
    }

    private static SolutionClassification classify(
        int coefficientRank,
        int augmentedRank,
        int variableCount
    ) {
        if (augmentedRank > coefficientRank) {
            return SolutionClassification.INCONSISTENT;
        }
        return coefficientRank == variableCount
            ? SolutionClassification.UNIQUE
            : SolutionClassification.UNDERDETERMINED;
    }

    private static List<List<Rational>> augmentedRows(
        List<List<Rational>> coefficients,
        List<Rational> rhs
    ) {
        List<List<Rational>> result = new ArrayList<>(coefficients.size());
        for (int row = 0; row < coefficients.size(); row++) {
            List<Rational> augmented = new ArrayList<>(
                coefficients.get(row).size() + 1);
            augmented.addAll(coefficients.get(row));
            augmented.add(rhs.get(row));
            result.add(augmented);
        }
        return result;
    }

    /** Exact Gaussian elimination used only to classify the represented system. */
    private static int rank(
        List<List<Rational>> source,
        WorkCounter work
    ) {
        Rational[][] matrix = new Rational[source.size()][];
        for (int row = 0; row < source.size(); row++) {
            matrix[row] = source.get(row).toArray(Rational[]::new);
        }
        int rows = matrix.length;
        int columns = matrix[0].length;
        int rank = 0;
        for (int column = 0; column < columns && rank < rows; column++) {
            int pivot = -1;
            for (int row = rank; row < rows; row++) {
                work.consume();
                if (!matrix[row][column].isZero()) {
                    pivot = row;
                    break;
                }
            }
            if (pivot < 0) {
                continue;
            }
            if (pivot != rank) {
                Rational[] swap = matrix[pivot];
                matrix[pivot] = matrix[rank];
                matrix[rank] = swap;
                work.consume();
            }
            Rational pivotValue = matrix[rank][column];
            for (int row = rank + 1; row < rows; row++) {
                work.consume();
                if (matrix[row][column].isZero()) {
                    continue;
                }
                Rational factor = matrix[row][column].divide(pivotValue);
                for (int current = column;
                        current < columns;
                        current++) {
                    work.consume();
                    matrix[row][current] = matrix[row][current].subtract(
                        factor.multiply(matrix[rank][current]));
                }
            }
            rank++;
        }
        return rank;
    }

    private static boolean roundTripMatches(
        List<AffineForm> normalizedRows,
        ExactLinearSystem represented,
        WorkCounter work
    ) {
        for (int row = 0; row < represented.equationCount(); row++) {
            Map<String, Rational> coefficients = new LinkedHashMap<>();
            for (int column = 0;
                    column < represented.variableCount();
                    column++) {
                work.consume();
                Rational coefficient = represented.coefficients().get(
                    row,
                    column);
                if (!coefficient.isZero()) {
                    coefficients.put(
                        represented.variables().get(column),
                        coefficient);
                }
            }
            AffineForm reconstructed = new AffineForm(
                coefficients,
                represented.rightHandSide().get(row).negate());
            if (!reconstructed.equals(normalizedRows.get(row))) {
                return false;
            }
        }
        return true;
    }

    private static Certificate certificate(
        List<Equation> equations,
        ExactLinearSystem represented
    ) {
        List<String> sourceEquations = equations.stream()
            .map(LinearSystemRepresentationBridge::formatEquation)
            .toList();
        List<List<String>> coefficientRows = represented.coefficients()
            .rows()
            .stream()
            .map(row -> row.stream().map(Rational::toString).toList())
            .toList();
        List<String> rhs = represented.rightHandSide()
            .values()
            .stream()
            .map(Rational::toString)
            .toList();

        StringBuilder payload = new StringBuilder();
        appendToken(payload, CERTIFICATE_SCHEMA);
        appendToken(payload, BRIDGE_ID);
        appendToken(payload, RELATION.name());
        appendToken(payload, Integer.toString(represented.equationCount()));
        appendToken(payload, Integer.toString(represented.variableCount()));
        sourceEquations.forEach(value -> appendToken(payload, value));
        represented.variables().forEach(value -> appendToken(payload, value));
        for (List<String> row : coefficientRows) {
            appendToken(payload, Integer.toString(row.size()));
            row.forEach(value -> appendToken(payload, value));
        }
        rhs.forEach(value -> appendToken(payload, value));
        appendToken(payload, Integer.toString(represented.coefficientRank()));
        appendToken(payload, Integer.toString(represented.augmentedRank()));
        appendToken(
            payload,
            represented.solutionClassification().name());

        return new Certificate(
            CERTIFICATE_SCHEMA,
            BRIDGE_ID,
            RELATION,
            sourceEquations,
            represented.variables(),
            coefficientRows,
            rhs,
            represented.equationCount(),
            represented.variableCount(),
            represented.coefficientRank(),
            represented.augmentedRank(),
            represented.solutionClassification(),
            sha256(payload.toString()));
    }

    private static String formatEquation(Equation equation) {
        return ExpressionFormatter.format(equation);
    }

    private static void appendToken(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Certificate(
        String schema,
        String bridgeId,
        Relation relation,
        List<String> sourceEquations,
        List<String> variableOrder,
        List<List<String>> coefficientRows,
        List<String> rightHandSide,
        int rowCount,
        int columnCount,
        int coefficientRank,
        int augmentedRank,
        SolutionClassification solutionClassification,
        String contentHash
    ) {
        public Certificate {
            if (schema == null || schema.isBlank()
                    || bridgeId == null || bridgeId.isBlank()
                    || contentHash == null || contentHash.isBlank()) {
                throw new IllegalArgumentException(
                    "certificate identities must not be blank");
            }
            relation = Objects.requireNonNull(relation, "relation");
            sourceEquations = textList(sourceEquations, "sourceEquations");
            variableOrder = textList(variableOrder, "variableOrder");
            coefficientRows = matrixText(
                coefficientRows,
                "coefficientRows");
            rightHandSide = textList(rightHandSide, "rightHandSide");
            solutionClassification = Objects.requireNonNull(
                solutionClassification,
                "solutionClassification");
            if (rowCount < 1
                    || columnCount < 1
                    || sourceEquations.size() != rowCount
                    || coefficientRows.size() != rowCount
                    || rightHandSide.size() != rowCount
                    || variableOrder.size() != columnCount
                    || coefficientRows.stream()
                        .anyMatch(row -> row.size() != columnCount)) {
                throw new IllegalArgumentException(
                    "certificate matrix dimensions are inconsistent");
            }
            int maximumCoefficientRank = Math.min(rowCount, columnCount);
            int maximumAugmentedRank = Math.min(rowCount, columnCount + 1);
            if (coefficientRank < 0
                    || coefficientRank > maximumCoefficientRank
                    || augmentedRank < coefficientRank
                    || augmentedRank > maximumAugmentedRank) {
                throw new IllegalArgumentException(
                    "certificate ranks are inconsistent");
            }
            SolutionClassification expected = classify(
                coefficientRank,
                augmentedRank,
                columnCount);
            if (solutionClassification != expected) {
                throw new IllegalArgumentException(
                    "certificate classification is inconsistent");
            }
        }

        private static List<String> textList(
            List<String> values,
            String field
        ) {
            Objects.requireNonNull(values, field);
            return values.stream()
                .map(value -> {
                    if (value == null || value.isBlank()) {
                        throw new IllegalArgumentException(
                            field + " entries must not be blank");
                    }
                    return value;
                })
                .toList();
        }

        private static List<List<String>> matrixText(
            List<List<String>> rows,
            String field
        ) {
            Objects.requireNonNull(rows, field);
            return rows.stream()
                .map(row -> textList(row, field + " row"))
                .toList();
        }
    }

    private record AffineForm(
        Map<String, Rational> coefficients,
        Rational constant
    ) {
        private AffineForm {
            Objects.requireNonNull(coefficients, "coefficients");
            constant = Objects.requireNonNull(constant, "constant");
            Map<String, Rational> normalized = new TreeMap<>();
            coefficients.forEach((variable, coefficient) -> {
                if (variable == null || variable.isBlank()) {
                    throw new IllegalArgumentException(
                        "affine variable must not be blank");
                }
                Rational exact = Objects.requireNonNull(
                    coefficient,
                    "coefficient");
                if (!exact.isZero()) {
                    normalized.merge(variable, exact, Rational::add);
                }
            });
            normalized.entrySet().removeIf(entry -> entry.getValue().isZero());
            coefficients = Map.copyOf(normalized);
        }

        private static AffineForm constant(Rational value) {
            return new AffineForm(Map.of(), value);
        }

        private static AffineForm variable(String name) {
            return new AffineForm(Map.of(name, Rational.ONE), Rational.ZERO);
        }

        private boolean isConstant() {
            return coefficients.isEmpty();
        }

        private Rational coefficient(String variable) {
            return coefficients.getOrDefault(variable, Rational.ZERO);
        }

        private AffineForm add(AffineForm other) {
            Map<String, Rational> result = new TreeMap<>(coefficients);
            other.coefficients.forEach((variable, coefficient) ->
                result.merge(variable, coefficient, Rational::add));
            return new AffineForm(result, constant.add(other.constant));
        }

        private AffineForm subtract(AffineForm other) {
            return add(other.scale(Rational.NEGATIVE_ONE));
        }

        private AffineForm scale(Rational factor) {
            if (factor.isZero()) {
                return constant(Rational.ZERO);
            }
            Map<String, Rational> result = new TreeMap<>();
            coefficients.forEach((variable, coefficient) ->
                result.put(variable, coefficient.multiply(factor)));
            return new AffineForm(result, constant.multiply(factor));
        }
    }

    private static final class WorkCounter {
        private final int configured;
        private int consumed;

        private WorkCounter(int configured) {
            this.configured = configured;
        }

        private void consume() {
            if (consumed >= configured) {
                throw new BudgetExceeded();
            }
            consumed++;
        }

        private WorkLedger ledger() {
            return WorkLedger.of(configured, consumed);
        }
    }

    private static final class BudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class NonlinearExpression extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        private NonlinearExpression(String detailCode) {
            this.detailCode = detailCode;
        }

        private String detailCode() {
            return detailCode;
        }
    }

    private static final class UnsupportedExpression extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String detailCode;

        private UnsupportedExpression(String detailCode) {
            this.detailCode = detailCode;
        }

        private String detailCode() {
            return detailCode;
        }
    }
}
