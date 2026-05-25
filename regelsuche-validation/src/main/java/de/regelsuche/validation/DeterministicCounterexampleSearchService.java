package de.regelsuche.validation;

import de.regelsuche.assumption.AssumptionSignature;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.linalg.Matrix;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic baseline implementation for counterexample search.
 *
 * <p>Combines edge-case and random numeric sampling plus a small
 * non-commutative matrix check for uppercase symbolic variables.</p>
 */
public class DeterministicCounterexampleSearchService implements CounterexampleSearchService {
    private static final double EPS = 1e-8;
    private static final List<Double> EDGE_VALUES = List.of(0.0, 1.0, -1.0, 2.0, -2.0, 0.5, -0.5);

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public CounterexampleSearchResult search(HypothesisInput hypothesis, CounterexampleBudget budget) {
        Expr left;
        Expr right;
        try {
            left = parser.parse(new InputRequest(InputType.TERM, hypothesis.leftExpression())).terms().getFirst();
            right = parser.parse(new InputRequest(InputType.TERM, hypothesis.rightExpression())).terms().getFirst();
        } catch (RuntimeException ex) {
            return CounterexampleSearchResult.noCounterexample();
        }

        List<String> attemptedSources = new ArrayList<>();
        Set<String> inferredAssumptions = new LinkedHashSet<>();
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(left, variables);
        collectVariables(right, variables);
        List<String> orderedVariables = new ArrayList<>(variables);
        List<AssumptionGuard> assumptionGuards = assumptionGuards(hypothesis.assumptions());

        if (budget.includeEdgeCases()) {
            attemptedSources.add("edge-cases");
            Optional<Counterexample> edgeCounterexample = searchNumericAssignments(
                left, right, orderedVariables, EDGE_VALUES, inferredAssumptions, assumptionGuards
            );
            if (edgeCounterexample.isPresent()) {
                return new CounterexampleSearchResult(
                    edgeCounterexample,
                    List.copyOf(inferredAssumptions),
                    attemptedSources
                );
            }
        }

        if (budget.numericRandomSamples() > 0) {
            attemptedSources.add("numeric-random");
            Random random = new Random(budget.randomSeed());
            List<Double> randomValues = new ArrayList<>(budget.numericRandomSamples());
            for (int i = 0; i < budget.numericRandomSamples(); i++) {
                randomValues.add(-5.0 + (10.0 * random.nextDouble()));
            }
            Optional<Counterexample> randomCounterexample = searchNumericAssignments(
                left, right, orderedVariables, randomValues, inferredAssumptions, assumptionGuards
            );
            if (randomCounterexample.isPresent()) {
                return new CounterexampleSearchResult(
                    randomCounterexample,
                    List.copyOf(inferredAssumptions),
                    attemptedSources
                );
            }
        }

        if (budget.includeMatrixAssignments()) {
            Optional<Counterexample> matrixCounterexample = tryMatrixCounterexample(
                left, right, orderedVariables, attemptedSources
            );
            if (matrixCounterexample.isPresent()) {
                return new CounterexampleSearchResult(
                    matrixCounterexample,
                    List.copyOf(inferredAssumptions),
                    attemptedSources
                );
            }
        }

        return new CounterexampleSearchResult(Optional.empty(), List.copyOf(inferredAssumptions), attemptedSources);
    }

    private Optional<Counterexample> searchNumericAssignments(
        Expr left,
        Expr right,
        List<String> orderedVariables,
        List<Double> values,
        Set<String> inferredAssumptions,
        List<AssumptionGuard> assumptionGuards
    ) {
        if (orderedVariables.isEmpty()) {
            Evaluation leftEvaluation = evaluate(left, Map.of());
            Evaluation rightEvaluation = evaluate(right, Map.of());
            if (leftEvaluation.defined() && rightEvaluation.defined()) {
                if (!leftEvaluation.value().approximatelyEquals(rightEvaluation.value())) {
                    return Optional.of(new Counterexample(List.of(), leftEvaluation.value().asText(), rightEvaluation.value().asText()));
                }
            }
            return Optional.empty();
        }

        int assignmentCount = Math.max(values.size(), orderedVariables.size());
        for (int i = 0; i < assignmentCount; i++) {
            Map<String, RuntimeValue> assignment = new HashMap<>();
            List<String> assignmentText = new ArrayList<>();
            int j = 0;
            for (String variable : orderedVariables) {
                double value = values.get((i + j) % values.size());
                assignment.put(variable, RuntimeValue.scalar(value));
                assignmentText.add(variable + "=" + trimDouble(value));
                j++;
            }
            if (violatesAssumptions(assignment, assumptionGuards)) {
                continue;
            }
            Evaluation leftEvaluation = evaluate(left, assignment);
            Evaluation rightEvaluation = evaluate(right, assignment);
            if (!leftEvaluation.defined() || !rightEvaluation.defined()) {
                inferNonZeroAssumptions(leftEvaluation, inferredAssumptions);
                inferNonZeroAssumptions(rightEvaluation, inferredAssumptions);
                continue;
            }
            if (!leftEvaluation.value().approximatelyEquals(rightEvaluation.value())) {
                return Optional.of(new Counterexample(
                    assignmentText,
                    leftEvaluation.value().asText(),
                    rightEvaluation.value().asText()
                ));
            }
        }
        return Optional.empty();
    }

    private Optional<Counterexample> tryMatrixCounterexample(
        Expr left,
        Expr right,
        List<String> orderedVariables,
        List<String> attemptedSources
    ) {
        if (orderedVariables.size() < 2 || orderedVariables.stream().anyMatch(v -> !isSingleUppercaseVariable(v))) {
            return Optional.empty();
        }
        attemptedSources.add("matrix-non-commutative");
        Map<String, RuntimeValue> assignment = new HashMap<>();
        assignment.put(orderedVariables.get(0), RuntimeValue.matrix(new Matrix(new double[][] {{1, 2}, {0, 1}})));
        assignment.put(orderedVariables.get(1), RuntimeValue.matrix(new Matrix(new double[][] {{1, 0}, {3, 1}})));
        for (int i = 2; i < orderedVariables.size(); i++) {
            assignment.put(orderedVariables.get(i), RuntimeValue.matrix(new Matrix(new double[][] {{1, 0}, {0, 1}})));
        }
        Evaluation leftEvaluation = evaluate(left, assignment);
        Evaluation rightEvaluation = evaluate(right, assignment);
        if (leftEvaluation.defined()
            && rightEvaluation.defined()
            && !leftEvaluation.value().approximatelyEquals(rightEvaluation.value())) {
            return Optional.of(new Counterexample(
                orderedVariables.stream().map(v -> v + "=M").toList(),
                leftEvaluation.value().asText(),
                rightEvaluation.value().asText()
            ));
        }
        return Optional.empty();
    }

    private static boolean isSingleUppercaseVariable(String name) {
        return name.length() == 1 && Character.isUpperCase(name.charAt(0));
    }

    private void collectVariables(Expr expression, Set<String> variables) {
        if (expression instanceof VariableExpr variableExpr) {
            variables.add(variableExpr.name());
            return;
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            collectVariables(binaryExpr.left(), variables);
            collectVariables(binaryExpr.right(), variables);
            return;
        }
        if (expression instanceof FunctionExpr functionExpr) {
            for (Expr argument : functionExpr.arguments()) {
                collectVariables(argument, variables);
            }
        }
    }

    private Evaluation evaluate(Expr expression, Map<String, RuntimeValue> variables) {
        if (expression instanceof NumberExpr numberExpr) {
            return Evaluation.defined(RuntimeValue.scalar(numberExpr.value()));
        }
        if (expression instanceof VariableExpr variableExpr) {
            RuntimeValue value = variables.get(variableExpr.name());
            return value == null ? Evaluation.defined(RuntimeValue.scalar(0.0)) : Evaluation.defined(value);
        }
        if (expression instanceof FunctionExpr functionExpr) {
            if (functionExpr.arguments().size() != 1) {
                return Evaluation.undefined(Set.of());
            }
            Evaluation argument = evaluate(functionExpr.arguments().getFirst(), variables);
            if (!argument.defined() || argument.value().kind() != RuntimeValue.Kind.SCALAR) {
                return Evaluation.undefined(argument.zeroDenominatorSymbols());
            }
            double x = argument.value().scalar();
            return switch (functionExpr.name()) {
                case "sin" -> Evaluation.defined(RuntimeValue.scalar(Math.sin(x)));
                case "cos" -> Evaluation.defined(RuntimeValue.scalar(Math.cos(x)));
                case "tan" -> Evaluation.defined(RuntimeValue.scalar(Math.tan(x)));
                case "log" -> x <= 0 ? Evaluation.undefined(Set.of()) : Evaluation.defined(RuntimeValue.scalar(Math.log10(x)));
                case "ln" -> x <= 0 ? Evaluation.undefined(Set.of()) : Evaluation.defined(RuntimeValue.scalar(Math.log(x)));
                case "sqrt" -> x < 0 ? Evaluation.undefined(Set.of()) : Evaluation.defined(RuntimeValue.scalar(Math.sqrt(x)));
                case "exp" -> Evaluation.defined(RuntimeValue.scalar(Math.exp(x)));
                case "abs" -> Evaluation.defined(RuntimeValue.scalar(Math.abs(x)));
                default -> Evaluation.undefined(Set.of());
            };
        }
        BinaryExpr binaryExpr = (BinaryExpr) expression;
        Evaluation left = evaluate(binaryExpr.left(), variables);
        Evaluation right = evaluate(binaryExpr.right(), variables);
        if (!left.defined() || !right.defined()) {
            Set<String> merged = new LinkedHashSet<>(left.zeroDenominatorSymbols());
            merged.addAll(right.zeroDenominatorSymbols());
            return Evaluation.undefined(merged);
        }
        RuntimeValue leftValue = left.value();
        RuntimeValue rightValue = right.value();
        return switch (binaryExpr.operator()) {
            case ADD -> add(leftValue, rightValue);
            case SUB -> subtract(leftValue, rightValue);
            case MUL -> multiply(leftValue, rightValue);
            case DIV -> divide(leftValue, rightValue, binaryExpr.right());
            case POW -> pow(leftValue, rightValue);
        };
    }

    private Evaluation add(RuntimeValue left, RuntimeValue right) {
        if (left.kind() == RuntimeValue.Kind.SCALAR && right.kind() == RuntimeValue.Kind.SCALAR) {
            return Evaluation.defined(RuntimeValue.scalar(left.scalar() + right.scalar()));
        }
        return Evaluation.undefined(Set.of());
    }

    private Evaluation subtract(RuntimeValue left, RuntimeValue right) {
        if (left.kind() == RuntimeValue.Kind.SCALAR && right.kind() == RuntimeValue.Kind.SCALAR) {
            return Evaluation.defined(RuntimeValue.scalar(left.scalar() - right.scalar()));
        }
        return Evaluation.undefined(Set.of());
    }

    private Evaluation multiply(RuntimeValue left, RuntimeValue right) {
        if (left.kind() == RuntimeValue.Kind.SCALAR && right.kind() == RuntimeValue.Kind.SCALAR) {
            return Evaluation.defined(RuntimeValue.scalar(left.scalar() * right.scalar()));
        }
        if (left.kind() == RuntimeValue.Kind.MATRIX && right.kind() == RuntimeValue.Kind.MATRIX) {
            return Evaluation.defined(RuntimeValue.matrix(left.matrix().multiply(right.matrix())));
        }
        return Evaluation.undefined(Set.of());
    }

    private Evaluation divide(RuntimeValue left, RuntimeValue right, Expr denominatorExpr) {
        if (left.kind() != RuntimeValue.Kind.SCALAR || right.kind() != RuntimeValue.Kind.SCALAR) {
            return Evaluation.undefined(Set.of());
        }
        if (Math.abs(right.scalar()) <= EPS) {
            Set<String> denominatorVariables = new LinkedHashSet<>();
            collectVariables(denominatorExpr, denominatorVariables);
            return Evaluation.undefined(denominatorVariables);
        }
        return Evaluation.defined(RuntimeValue.scalar(left.scalar() / right.scalar()));
    }

    private Evaluation pow(RuntimeValue left, RuntimeValue right) {
        if (left.kind() != RuntimeValue.Kind.SCALAR || right.kind() != RuntimeValue.Kind.SCALAR) {
            return Evaluation.undefined(Set.of());
        }
        double value = Math.pow(left.scalar(), right.scalar());
        if (!Double.isFinite(value)) {
            return Evaluation.undefined(Set.of());
        }
        return Evaluation.defined(RuntimeValue.scalar(value));
    }

    private void inferNonZeroAssumptions(Evaluation evaluation, Set<String> inferredAssumptions) {
        for (String symbol : evaluation.zeroDenominatorSymbols()) {
            inferredAssumptions.add(AssumptionSignature.normalizeExpression(symbol + " != 0"));
        }
    }

    private List<AssumptionGuard> assumptionGuards(List<String> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return List.of();
        }
        List<AssumptionGuard> guards = new ArrayList<>();
        for (String assumption : AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions()) {
            int notEquals = assumption.indexOf(" != ");
            if (notEquals < 0) {
                continue;
            }
            String left = assumption.substring(0, notEquals).trim();
            String right = assumption.substring(notEquals + 4).trim();
            if (right.equals("0") && !left.isBlank()) {
                guards.add(new AssumptionGuard(left));
            }
        }
        return List.copyOf(guards);
    }

    private boolean violatesAssumptions(Map<String, RuntimeValue> assignment, List<AssumptionGuard> guards) {
        for (AssumptionGuard guard : guards) {
            RuntimeValue value = assignment.get(guard.nonZeroSymbol());
            if (value != null && value.kind() == RuntimeValue.Kind.SCALAR && Math.abs(value.scalar()) <= EPS) {
                return true;
            }
        }
        return false;
    }

    private static String trimDouble(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private record Evaluation(RuntimeValue value, boolean defined, Set<String> zeroDenominatorSymbols) {
        static Evaluation defined(RuntimeValue value) {
            return new Evaluation(value, true, Set.of());
        }

        static Evaluation undefined(Set<String> zeroDenominatorSymbols) {
            return new Evaluation(RuntimeValue.scalar(Double.NaN), false, Set.copyOf(zeroDenominatorSymbols));
        }
    }

    private record AssumptionGuard(String nonZeroSymbol) {
    }

    private record RuntimeValue(Kind kind, double scalar, Matrix matrix) {
        enum Kind {
            SCALAR,
            MATRIX
        }

        static RuntimeValue scalar(double value) {
            return new RuntimeValue(Kind.SCALAR, value, null);
        }

        static RuntimeValue matrix(Matrix value) {
            return new RuntimeValue(Kind.MATRIX, 0.0, value);
        }

        boolean approximatelyEquals(RuntimeValue other) {
            if (kind != other.kind) {
                return false;
            }
            if (kind == Kind.SCALAR) {
                if (!Double.isFinite(scalar) || !Double.isFinite(other.scalar)) {
                    return false;
                }
                return Math.abs(scalar - other.scalar) <= EPS;
            }
            return matrix.equals(other.matrix);
        }

        String asText() {
            if (kind == Kind.SCALAR) {
                return trimDouble(scalar);
            }
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < matrix.rows(); i++) {
                if (i > 0) {
                    builder.append(";");
                }
                for (int j = 0; j < matrix.columns(); j++) {
                    if (j > 0) {
                        builder.append(",");
                    }
                    builder.append(trimDouble(matrix.get(i, j)));
                }
            }
            return builder.append("]").toString();
        }
    }
}
