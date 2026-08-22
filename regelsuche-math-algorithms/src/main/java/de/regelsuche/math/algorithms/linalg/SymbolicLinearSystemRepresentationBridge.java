package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.Monomial;
import de.regelsuche.math.algorithms.equivalence.Polynomial;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialMatrix;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.PolynomialVector;
import de.regelsuche.math.algorithms.linalg.SymbolicLinearSystem.RowOrigin;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.representation.RepresentationBridge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exact polynomial bridge for systems linear in explicitly declared unknowns.
 *
 * <p>Symbols not declared as unknown coordinates remain scalar parameters. For
 * example, declaring {@code [x,y]} makes {@code a*x+b*y=lambda*x} linear with
 * coefficient {@code a-lambda} for {@code x}; no variable role is inferred from
 * spelling. This provides the typed boundary required before eigenproblem or
 * quantum-compatible structure recognition.</p>
 */
public final class SymbolicLinearSystemRepresentationBridge implements
        RepresentationBridge<
            SymbolicLinearSystemRepresentationBridge.Source,
            SymbolicLinearSystem,
            SymbolicLinearSystemRepresentationBridge.Certificate> {

    public static final String BRIDGE_ID =
        "declared-unknown-symbolic-linear-system/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.symbolic-linear-system-certificate/v1";
    private static final Relation RELATION =
        Relation.SOLUTION_SET_EQUIVALENCE;
    private static final int MAX_EXPONENT = 20;

    @Override
    public Result<SymbolicLinearSystem, Certificate> analyze(
        Source source,
        Budget budget
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        WorkCounter work = new WorkCounter(budget.maxWorkUnits());
        try {
            Set<String> unknownSet = Set.copyOf(source.unknowns());
            List<Polynomial> normalizedRows = new ArrayList<>(
                source.equations().size());
            for (Equation equation : source.equations()) {
                Polynomial left = polynomial(equation.left(), work);
                Polynomial right = polynomial(equation.right(), work);
                normalizedRows.add(left.subtract(right));
            }

            List<List<Polynomial>> coefficientRows = new ArrayList<>(
                normalizedRows.size());
            List<Polynomial> rightHandSide = new ArrayList<>(
                normalizedRows.size());
            List<RowOrigin> rowOrigins = new ArrayList<>(
                normalizedRows.size());
            Set<String> scalarParameters = new TreeSet<>();
            for (int rowIndex = 0;
                    rowIndex < normalizedRows.size();
                    rowIndex++) {
                LinearRow row = splitLinear(
                    normalizedRows.get(rowIndex),
                    source.unknowns(),
                    unknownSet,
                    work);
                coefficientRows.add(row.coefficients());
                rightHandSide.add(row.rightHandSide());
                row.coefficients().forEach(coefficient ->
                    scalarParameters.addAll(coefficient.variables()));
                scalarParameters.addAll(row.rightHandSide().variables());
                rowOrigins.add(new RowOrigin(
                    rowIndex,
                    ExpressionFormatter.format(
                        source.equations().get(rowIndex))));
            }

            SymbolicLinearSystem represented = new SymbolicLinearSystem(
                new PolynomialMatrix(coefficientRows),
                source.unknowns(),
                new PolynomialVector(rightHandSide),
                List.copyOf(scalarParameters),
                rowOrigins);
            if (!roundTripMatches(normalizedRows, represented, work)) {
                return Result.withoutRepresentation(
                    Status.INVALID_CERTIFICATE,
                    work.ledger(),
                    "SYMBOLIC_LINEAR_ROUND_TRIP_MISMATCH");
            }
            Certificate certificate = certificate(source, represented);
            return Result.represented(
                represented,
                certificate,
                RELATION,
                work.ledger(),
                "DECLARED_UNKNOWNS_REPRESENTED_SYMBOLICALLY");
        } catch (BudgetExceeded exception) {
            return Result.withoutRepresentation(
                Status.BUDGET_INCONCLUSIVE,
                work.ledger(),
                "SYMBOLIC_LINEAR_WORK_BUDGET_EXHAUSTED");
        } catch (NonlinearExpression exception) {
            return Result.withoutRepresentation(
                Status.NONLINEAR,
                work.ledger(),
                exception.detailCode());
        } catch (UnsupportedExpression exception) {
            return Result.withoutRepresentation(
                Status.DOMAIN_UNSUPPORTED,
                work.ledger(),
                exception.detailCode());
        }
    }

    @Override
    public boolean verify(
        Source source,
        Result<SymbolicLinearSystem, Certificate> result
    ) {
        if (source == null
                || result == null
                || result.status() != Status.REPRESENTED
                || result.relation().orElse(null) != RELATION) {
            return false;
        }
        try {
            return analyze(
                source,
                new Budget(result.work().configuredWorkUnits()))
                .equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static Polynomial polynomial(
        Expr expression,
        WorkCounter work
    ) {
        work.consume();
        if (expression instanceof NumberExpr number) {
            if (!Double.isFinite(number.value())) {
                throw new UnsupportedExpression("NON_FINITE_NUMBER");
            }
            return Polynomial.constant(Rational.fromDouble(number.value()));
        }
        if (expression instanceof VariableExpr variable) {
            return Polynomial.variable(variable.name());
        }
        if (expression instanceof FunctionExpr) {
            throw new UnsupportedExpression(
                "FUNCTION_OUTSIDE_SYMBOLIC_POLYNOMIAL_FRAGMENT");
        }
        if (!(expression instanceof BinaryExpr binary)) {
            throw new UnsupportedExpression("UNKNOWN_EXPRESSION_NODE");
        }
        if (binary.operator() == BinaryOperator.POW) {
            Polynomial base = polynomial(binary.left(), work);
            return base.pow(integerExponent(binary.right(), work));
        }
        Polynomial left = polynomial(binary.left(), work);
        Polynomial right = polynomial(binary.right(), work);
        return switch (binary.operator()) {
            case ADD -> left.add(right);
            case SUB -> left.subtract(right);
            case MUL -> left.multiply(right);
            case DIV -> divideByConstant(left, right);
            case POW -> throw new IllegalStateException(
                "power handled before binary recursion");
        };
    }

    private static Polynomial divideByConstant(
        Polynomial numerator,
        Polynomial denominator
    ) {
        Optional<Rational> constant = constantValue(denominator);
        if (constant.isEmpty()) {
            throw new UnsupportedExpression(
                "SYMBOLIC_OR_NON_CONSTANT_DENOMINATOR");
        }
        if (constant.orElseThrow().isZero()) {
            throw new UnsupportedExpression("EXPLICIT_DIVISION_BY_ZERO");
        }
        return numerator.multiply(
            Rational.ONE.divide(constant.orElseThrow()));
    }

    private static Optional<Rational> constantValue(Polynomial polynomial) {
        if (polynomial.isZero()) {
            return Optional.of(Rational.ZERO);
        }
        if (polynomial.terms().size() != 1) {
            return Optional.empty();
        }
        Map.Entry<Monomial, Rational> entry = polynomial.terms()
            .entrySet()
            .iterator()
            .next();
        return entry.getKey().powers().isEmpty()
            ? Optional.of(entry.getValue())
            : Optional.empty();
    }

    private static int integerExponent(Expr expression, WorkCounter work) {
        work.consume();
        if (!(expression instanceof NumberExpr number)
                || !Double.isFinite(number.value())) {
            throw new UnsupportedExpression(
                "NON_LITERAL_SYMBOLIC_POLYNOMIAL_EXPONENT");
        }
        double value = number.value();
        int exponent = (int) value;
        if (Math.abs(value - exponent) > 1e-9
                || exponent < 0
                || exponent > MAX_EXPONENT) {
            throw new UnsupportedExpression(
                "EXPONENT_OUTSIDE_SYMBOLIC_POLYNOMIAL_FRAGMENT");
        }
        return exponent;
    }

    private static LinearRow splitLinear(
        Polynomial polynomial,
        List<String> unknowns,
        Set<String> unknownSet,
        WorkCounter work
    ) {
        Map<String, Polynomial> coefficients = new LinkedHashMap<>();
        unknowns.forEach(unknown ->
            coefficients.put(unknown, Polynomial.zero()));
        Polynomial constant = Polynomial.zero();
        for (Map.Entry<Monomial, Rational> term
                : polynomial.terms().entrySet()) {
            work.consume();
            Monomial monomial = term.getKey();
            int unknownDegree = 0;
            String selectedUnknown = null;
            for (String unknown : unknownSet) {
                work.consume();
                int exponent = monomial.exponentOf(unknown);
                unknownDegree = Math.addExact(unknownDegree, exponent);
                if (exponent > 0) {
                    selectedUnknown = unknown;
                }
            }
            if (unknownDegree == 0) {
                constant = constant.add(Polynomial.term(
                    monomial,
                    term.getValue()));
                continue;
            }
            if (unknownDegree != 1 || selectedUnknown == null) {
                throw new NonlinearExpression(
                    "NONLINEAR_IN_DECLARED_UNKNOWNS");
            }
            Monomial parameterMonomial = monomial.without(selectedUnknown);
            Polynomial coefficientTerm = Polynomial.term(
                parameterMonomial,
                term.getValue());
            coefficients.put(
                selectedUnknown,
                coefficients.get(selectedUnknown).add(coefficientTerm));
        }
        List<Polynomial> orderedCoefficients = unknowns.stream()
            .map(coefficients::get)
            .toList();
        return new LinearRow(
            orderedCoefficients,
            constant.multiply(Rational.NEGATIVE_ONE));
    }

    private static boolean roundTripMatches(
        List<Polynomial> normalizedRows,
        SymbolicLinearSystem represented,
        WorkCounter work
    ) {
        for (int row = 0; row < represented.equationCount(); row++) {
            Polynomial reconstructed = Polynomial.zero();
            for (int column = 0;
                    column < represented.unknownCount();
                    column++) {
                work.consume();
                reconstructed = reconstructed.add(
                    represented.coefficients().get(row, column)
                        .multiply(Polynomial.variable(
                            represented.unknowns().get(column))));
            }
            reconstructed = reconstructed.subtract(
                represented.rightHandSide().get(row));
            if (!reconstructed.equals(normalizedRows.get(row))) {
                return false;
            }
        }
        return true;
    }

    private static Certificate certificate(
        Source source,
        SymbolicLinearSystem represented
    ) {
        List<String> sourceEquations = source.equations().stream()
            .map(ExpressionFormatter::format)
            .toList();
        List<List<String>> coefficientRows = represented.coefficients()
            .entries()
            .stream()
            .map(row -> row.stream()
                .map(Polynomial::toCanonicalString)
                .toList())
            .toList();
        List<String> rightHandSide = represented.rightHandSide()
            .values()
            .stream()
            .map(Polynomial::toCanonicalString)
            .toList();
        StringBuilder payload = new StringBuilder();
        append(payload, CERTIFICATE_SCHEMA);
        append(payload, BRIDGE_ID);
        append(payload, RELATION.name());
        append(payload, Integer.toString(sourceEquations.size()));
        sourceEquations.forEach(value -> append(payload, value));
        append(payload, Integer.toString(represented.unknowns().size()));
        represented.unknowns().forEach(value -> append(payload, value));
        append(payload, Integer.toString(
            represented.scalarParameters().size()));
        represented.scalarParameters().forEach(value -> append(payload, value));
        for (List<String> row : coefficientRows) {
            append(payload, Integer.toString(row.size()));
            row.forEach(value -> append(payload, value));
        }
        rightHandSide.forEach(value -> append(payload, value));
        return new Certificate(
            CERTIFICATE_SCHEMA,
            BRIDGE_ID,
            RELATION,
            sourceEquations,
            represented.unknowns(),
            represented.scalarParameters(),
            coefficientRows,
            rightHandSide,
            sha256(payload.toString()));
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Source(
        List<Equation> equations,
        List<String> unknowns
    ) {
        public Source {
            equations = List.copyOf(Objects.requireNonNull(
                equations,
                "equations"));
            unknowns = validatedUnknowns(unknowns);
            if (equations.isEmpty()) {
                throw new IllegalArgumentException(
                    "symbolic system requires at least one equation");
            }
        }

        private static List<String> validatedUnknowns(List<String> values) {
            Objects.requireNonNull(values, "unknowns");
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                    "at least one unknown must be declared");
            }
            Set<String> unique = new LinkedHashSet<>();
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        "unknown names must not be blank");
                }
                if (!unique.add(value.trim())) {
                    throw new IllegalArgumentException(
                        "unknown names must be unique");
                }
            }
            return List.copyOf(unique);
        }
    }

    public record Certificate(
        String schema,
        String bridgeId,
        Relation relation,
        List<String> sourceEquations,
        List<String> unknownOrder,
        List<String> scalarParameters,
        List<List<String>> coefficientRows,
        List<String> rightHandSide,
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
            unknownOrder = textList(unknownOrder, "unknownOrder");
            int unknownCount = unknownOrder.size();
            scalarParameters = textListAllowEmpty(
                scalarParameters,
                "scalarParameters");
            coefficientRows = matrixText(coefficientRows);
            rightHandSide = textList(rightHandSide, "rightHandSide");
            if (sourceEquations.size() != coefficientRows.size()
                    || sourceEquations.size() != rightHandSide.size()
                    || coefficientRows.stream()
                        .anyMatch(row -> row.size() != unknownCount)) {
                throw new IllegalArgumentException(
                    "certificate dimensions are inconsistent");
            }
        }

        private static List<String> textList(
            List<String> values,
            String field
        ) {
            List<String> retained = textListAllowEmpty(values, field);
            if (retained.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            return retained;
        }

        private static List<String> textListAllowEmpty(
            List<String> values,
            String field
        ) {
            Objects.requireNonNull(values, field);
            return values.stream().map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        field + " entries must not be blank");
                }
                return value;
            }).toList();
        }

        private static List<List<String>> matrixText(
            List<List<String>> rows
        ) {
            Objects.requireNonNull(rows, "coefficientRows");
            return rows.stream()
                .map(row -> textList(row, "coefficient row"))
                .toList();
        }
    }

    private record LinearRow(
        List<Polynomial> coefficients,
        Polynomial rightHandSide
    ) {
        private LinearRow {
            coefficients = List.copyOf(coefficients);
            rightHandSide = Objects.requireNonNull(
                rightHandSide,
                "rightHandSide");
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
