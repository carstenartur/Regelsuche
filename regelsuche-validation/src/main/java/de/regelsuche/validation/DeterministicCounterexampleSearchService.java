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
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private static final List<Double> EDGE_VALUES = List.of(0.0, 1.0, -1.0, 2.0, -2.0);
    private static final List<Double> RATIONAL_VALUES = List.of(0.5, -0.5, 2.0 / 3.0, -2.0 / 3.0);

    private final ExpressionParser parser = new ExpressionParser();

    @Override
    public CounterexampleSearchResult search(HypothesisInput hypothesis, CounterexampleBudget budget) {
        Expr left;
        Expr right;
        try {
            left = parser.parse(new InputRequest(InputType.TERM, hypothesis.leftExpression())).terms().getFirst();
            right = parser.parse(new InputRequest(InputType.TERM, hypothesis.rightExpression())).terms().getFirst();
        } catch (RuntimeException ex) {
            return CounterexampleSearchResult.inconclusive("unsupported expression: " + ex.getMessage());
        }

        List<String> attemptedSources = new ArrayList<>();
        Map<String, CounterexampleSearchService.TypedAssumption> inferredAssumptions = new LinkedHashMap<>();
        Set<String> variables = new LinkedHashSet<>();
        collectVariables(left, variables);
        collectVariables(right, variables);
        List<String> orderedVariables = new ArrayList<>(variables);
        List<AssumptionGuard> assumptionGuards = assumptionGuards(hypothesis.assumptions());

        if (budget.includeBoundaryValues()) {
            String source = "numeric-boundary-values";
            attemptedSources.add(source);
            Optional<Counterexample> edgeCounterexample = searchNumericAssignments(
                left, right, orderedVariables, EDGE_VALUES, inferredAssumptions, assumptionGuards, source
            );
            if (edgeCounterexample.isPresent()) {
                return CounterexampleSearchResult.counterexampleFound(
                    edgeCounterexample.get(),
                    inferredAssumptionExpressions(inferredAssumptions),
                    attemptedSources,
                    typedAssumptions(inferredAssumptions)
                );
            }
        }

        if (budget.includeRationals()) {
            String source = "rational-samples";
            attemptedSources.add(source);
            Optional<Counterexample> rationalCounterexample = searchNumericAssignments(
                left, right, orderedVariables, RATIONAL_VALUES, inferredAssumptions, assumptionGuards, source
            );
            if (rationalCounterexample.isPresent()) {
                return CounterexampleSearchResult.counterexampleFound(
                    rationalCounterexample.get(),
                    inferredAssumptionExpressions(inferredAssumptions),
                    attemptedSources,
                    typedAssumptions(inferredAssumptions)
                );
            }
        }

        if (budget.maxNumericSamples() > 0) {
            String source = "numeric-random";
            attemptedSources.add(source);
            Random random = new Random(budget.randomSeed());
            List<Double> randomValues = new ArrayList<>(budget.maxNumericSamples());
            for (int i = 0; i < budget.maxNumericSamples(); i++) {
                randomValues.add(-5.0 + (10.0 * random.nextDouble()));
            }
            Optional<Counterexample> randomCounterexample = searchNumericAssignments(
                left, right, orderedVariables, randomValues, inferredAssumptions, assumptionGuards, source
            );
            if (randomCounterexample.isPresent()) {
                return CounterexampleSearchResult.counterexampleFound(
                    randomCounterexample.get(),
                    inferredAssumptionExpressions(inferredAssumptions),
                    attemptedSources,
                    typedAssumptions(inferredAssumptions)
                );
            }
        }

        if (budget.includeMatrices() && budget.maxMatrixDimension() >= 2) {
            Optional<Counterexample> matrixCounterexample = tryMatrixCounterexample(
                left, right, orderedVariables, attemptedSources
            );
            if (matrixCounterexample.isPresent()) {
                return CounterexampleSearchResult.counterexampleFound(
                    matrixCounterexample.get(),
                    inferredAssumptionExpressions(inferredAssumptions),
                    attemptedSources,
                    typedAssumptions(inferredAssumptions)
                );
            }
        }

        if (budget.includeComplex()) {
            Optional<Counterexample> complexCounterexample = tryComplexCounterexample(
                left, right, orderedVariables, attemptedSources, assumptionGuards
            );
            if (complexCounterexample.isPresent()) {
                return CounterexampleSearchResult.counterexampleFound(
                    complexCounterexample.get(),
                    inferredAssumptionExpressions(inferredAssumptions),
                    attemptedSources,
                    typedAssumptions(inferredAssumptions)
                );
            }
        }

        if (attemptedSources.isEmpty()) {
            return new CounterexampleSearchResult(
                CounterexampleSearchService.Status.INCONCLUSIVE,
                Optional.empty(),
                inferredAssumptionExpressions(inferredAssumptions),
                attemptedSources,
                "no executable counterexample source was available",
                typedAssumptions(inferredAssumptions)
            );
        }
        return CounterexampleSearchResult.noCounterexampleFound(
            inferredAssumptionExpressions(inferredAssumptions),
            attemptedSources,
            typedAssumptions(inferredAssumptions)
        );
    }

    private Optional<Counterexample> searchNumericAssignments(
        Expr left,
        Expr right,
        List<String> orderedVariables,
        List<Double> values,
        Map<String, CounterexampleSearchService.TypedAssumption> inferredAssumptions,
        List<AssumptionGuard> assumptionGuards,
        String source
    ) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
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
                inferAssumptions(leftEvaluation, inferredAssumptions, source);
                inferAssumptions(rightEvaluation, inferredAssumptions, source);
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

    private Optional<Counterexample> tryComplexCounterexample(
        Expr left,
        Expr right,
        List<String> orderedVariables,
        List<String> attemptedSources,
        List<AssumptionGuard> assumptionGuards
    ) {
        if (orderedVariables.isEmpty()) {
            return Optional.empty();
        }
        attemptedSources.add("complex-samples");
        List<Complex> values = List.of(
            new Complex(0.0, 1.0),
            new Complex(0.0, -1.0),
            new Complex(-1.0, 1.0),
            new Complex(1.0, -1.0)
        );
        for (int i = 0; i < Math.max(values.size(), orderedVariables.size()); i++) {
            Map<String, RuntimeValue> assignment = new HashMap<>();
            List<String> assignmentText = new ArrayList<>();
            int j = 0;
            for (String variable : orderedVariables) {
                Complex value = values.get((i + j) % values.size());
                assignment.put(variable, RuntimeValue.complex(value));
                assignmentText.add(variable + "=" + value.asText());
                j++;
            }
            if (violatesAssumptions(assignment, assumptionGuards)) {
                continue;
            }
            Evaluation leftEvaluation = evaluate(left, assignment);
            Evaluation rightEvaluation = evaluate(right, assignment);
            if (leftEvaluation.defined()
                && rightEvaluation.defined()
                && !leftEvaluation.value().approximatelyEquals(rightEvaluation.value())) {
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
            if (!argument.defined()) {
                return Evaluation.undefined(argument.inferredAssumptions());
            }
            if (argument.value().kind() == RuntimeValue.Kind.COMPLEX) {
                Complex z = argument.value().complex();
                return switch (functionExpr.name()) {
                    case "sqrt" -> Evaluation.defined(RuntimeValue.complex(z.sqrt()));
                    case "abs" -> Evaluation.defined(RuntimeValue.scalar(z.abs()));
                    default -> Evaluation.undefined(Set.of());
                };
            }
            if (argument.value().kind() != RuntimeValue.Kind.SCALAR) {
                return Evaluation.undefined(argument.inferredAssumptions());
            }
            double x = argument.value().scalar();
            return switch (functionExpr.name()) {
                case "sin" -> Evaluation.defined(RuntimeValue.scalar(Math.sin(x)));
                case "cos" -> Evaluation.defined(RuntimeValue.scalar(Math.cos(x)));
                case "tan" -> Evaluation.defined(RuntimeValue.scalar(Math.tan(x)));
                case "log" -> x <= 0
                    ? Evaluation.undefined(domainAssumption(functionExpr.arguments().getFirst(),
                        CounterexampleSearchService.AssumptionKind.POSITIVE, "> 0"))
                    : Evaluation.defined(RuntimeValue.scalar(Math.log10(x)));
                case "ln" -> x <= 0
                    ? Evaluation.undefined(domainAssumption(functionExpr.arguments().getFirst(),
                        CounterexampleSearchService.AssumptionKind.POSITIVE, "> 0"))
                    : Evaluation.defined(RuntimeValue.scalar(Math.log(x)));
                case "sqrt" -> x < 0
                    ? Evaluation.undefined(domainAssumption(functionExpr.arguments().getFirst(),
                        CounterexampleSearchService.AssumptionKind.NON_NEGATIVE, ">= 0"))
                    : Evaluation.defined(RuntimeValue.scalar(Math.sqrt(x)));
                case "exp" -> Evaluation.defined(RuntimeValue.scalar(Math.exp(x)));
                case "abs" -> Evaluation.defined(RuntimeValue.scalar(Math.abs(x)));
                default -> Evaluation.undefined(Set.of());
            };
        }
        BinaryExpr binaryExpr = (BinaryExpr) expression;
        Evaluation left = evaluate(binaryExpr.left(), variables);
        Evaluation right = evaluate(binaryExpr.right(), variables);
        if (!left.defined() || !right.defined()) {
            Set<InferenceCandidate> merged = new LinkedHashSet<>(left.inferredAssumptions());
            merged.addAll(right.inferredAssumptions());
            return Evaluation.undefined(merged);
        }
        RuntimeValue leftValue = left.value();
        RuntimeValue rightValue = right.value();
        return switch (binaryExpr.operator()) {
            case ADD -> add(leftValue, rightValue);
            case SUB -> subtract(leftValue, rightValue);
            case MUL -> multiply(leftValue, rightValue);
            case DIV -> divide(leftValue, rightValue, binaryExpr.right());
            case POW -> pow(leftValue, rightValue, binaryExpr.left());
        };
    }

    private Evaluation add(RuntimeValue left, RuntimeValue right) {
        if (left.kind() == RuntimeValue.Kind.SCALAR && right.kind() == RuntimeValue.Kind.SCALAR) {
            return Evaluation.defined(RuntimeValue.scalar(left.scalar() + right.scalar()));
        }
        if (left.isNumeric() && right.isNumeric()) {
            return Evaluation.defined(RuntimeValue.complex(left.asComplex().add(right.asComplex())));
        }
        return Evaluation.undefined(Set.of());
    }

    private Evaluation subtract(RuntimeValue left, RuntimeValue right) {
        if (left.kind() == RuntimeValue.Kind.SCALAR && right.kind() == RuntimeValue.Kind.SCALAR) {
            return Evaluation.defined(RuntimeValue.scalar(left.scalar() - right.scalar()));
        }
        if (left.isNumeric() && right.isNumeric()) {
            return Evaluation.defined(RuntimeValue.complex(left.asComplex().subtract(right.asComplex())));
        }
        return Evaluation.undefined(Set.of());
    }

    private Evaluation multiply(RuntimeValue left, RuntimeValue right) {
        if (left.kind() == RuntimeValue.Kind.SCALAR && right.kind() == RuntimeValue.Kind.SCALAR) {
            return Evaluation.defined(RuntimeValue.scalar(left.scalar() * right.scalar()));
        }
        if (left.isNumeric() && right.isNumeric()) {
            return Evaluation.defined(RuntimeValue.complex(left.asComplex().multiply(right.asComplex())));
        }
        if (left.kind() == RuntimeValue.Kind.MATRIX && right.kind() == RuntimeValue.Kind.MATRIX) {
            return Evaluation.defined(RuntimeValue.matrix(left.matrix().multiply(right.matrix())));
        }
        return Evaluation.undefined(Set.of());
    }

    private Evaluation divide(RuntimeValue left, RuntimeValue right, Expr denominatorExpr) {
        if (!left.isNumeric() || !right.isNumeric()) {
            return Evaluation.undefined(Set.of());
        }
        if (right.asComplex().abs() <= EPS) {
            return Evaluation.undefined(nonZeroAssumption(denominatorExpr));
        }
        if (left.kind() == RuntimeValue.Kind.SCALAR && right.kind() == RuntimeValue.Kind.SCALAR) {
            return Evaluation.defined(RuntimeValue.scalar(left.scalar() / right.scalar()));
        }
        return Evaluation.defined(RuntimeValue.complex(left.asComplex().divide(right.asComplex())));
    }

    private Evaluation pow(RuntimeValue left, RuntimeValue right, Expr baseExpr) {
        if (!left.isNumeric() || right.kind() != RuntimeValue.Kind.SCALAR) {
            return Evaluation.undefined(Set.of());
        }
        if (left.kind() == RuntimeValue.Kind.COMPLEX && right.scalar() == Math.rint(right.scalar())) {
            return Evaluation.defined(RuntimeValue.complex(left.complex().pow((int) right.scalar())));
        }
        if (left.kind() == RuntimeValue.Kind.SCALAR) {
            if (left.scalar() < 0.0 && Math.abs(right.scalar() - Math.rint(right.scalar())) > EPS) {
                return Evaluation.undefined(domainAssumption(
                    baseExpr,
                    CounterexampleSearchService.AssumptionKind.NON_NEGATIVE,
                    ">= 0"
                ));
            }
            if (Math.abs(left.scalar()) <= EPS && right.scalar() < 0.0) {
                return Evaluation.undefined(nonZeroAssumption(baseExpr));
            }
        }
        double value = Math.pow(left.scalar(), right.scalar());
        if (!Double.isFinite(value)) {
            return Evaluation.undefined(Set.of());
        }
        return Evaluation.defined(RuntimeValue.scalar(value));
    }

    private void inferAssumptions(
        Evaluation evaluation,
        Map<String, CounterexampleSearchService.TypedAssumption> inferredAssumptions,
        String source
    ) {
        for (InferenceCandidate candidate : evaluation.inferredAssumptions()) {
            inferredAssumptions.merge(candidate.normalizedPredicate(),
                typedAssumption(candidate, source),
                this::mergeTypedAssumptions);
        }
    }

    private List<AssumptionGuard> assumptionGuards(List<String> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return List.of();
        }
        List<AssumptionGuard> guards = new ArrayList<>();
        for (String assumption : AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions()) {
            guards.addAll(guardsFor(assumption));
        }
        return List.copyOf(guards);
    }

    private boolean violatesAssumptions(Map<String, RuntimeValue> assignment, List<AssumptionGuard> guards) {
        for (AssumptionGuard guard : guards) {
            RuntimeValue value = assignment.get(guard.symbol());
            if (value != null && value.isNumeric() && value.asComplex().abs() <= EPS) {
                if (guard.relation() == Relation.NON_ZERO) {
                    return true;
                }
                if (guard.relation() == Relation.POSITIVE || guard.relation() == Relation.NON_NEGATIVE) {
                    return true;
                }
            }
            if (value != null && value.kind() == RuntimeValue.Kind.SCALAR) {
                double scalar = value.scalar();
                if (guard.relation() == Relation.POSITIVE && scalar <= 0.0) {
                    return true;
                }
                if (guard.relation() == Relation.NON_NEGATIVE && scalar < 0.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<AssumptionGuard> guardsFor(String assumption) {
        if (assumption.endsWith(" != 0")) {
            String left = assumption.substring(0, assumption.length() - " != 0".length()).trim();
            return left.isBlank() ? List.of() : List.of(new AssumptionGuard(left, Relation.NON_ZERO));
        }
        if (assumption.endsWith(" > 0")) {
            String left = assumption.substring(0, assumption.length() - " > 0".length()).trim();
            return left.isBlank() ? List.of() : List.of(new AssumptionGuard(left, Relation.POSITIVE));
        }
        if (assumption.endsWith(" >= 0")) {
            String left = assumption.substring(0, assumption.length() - " >= 0".length()).trim();
            return left.isBlank() ? List.of() : List.of(new AssumptionGuard(left, Relation.NON_NEGATIVE));
        }
        return List.of();
    }

    private Set<InferenceCandidate> nonZeroAssumption(Expr expression) {
        String subjectExpression = ExpressionFormatter.format(expression);
        Set<String> affectedVariables = new LinkedHashSet<>();
        collectVariables(expression, affectedVariables);
        return Set.of(new InferenceCandidate(
            CounterexampleSearchService.AssumptionKind.NON_ZERO,
            subjectExpression,
            AssumptionSignature.normalizeExpression(subjectExpression + " != 0"),
            Set.copyOf(affectedVariables)
        ));
    }

    private Set<InferenceCandidate> domainAssumption(
        Expr expression,
        CounterexampleSearchService.AssumptionKind kind,
        String relation
    ) {
        String subjectExpression = ExpressionFormatter.format(expression);
        Set<String> affectedVariables = new LinkedHashSet<>();
        collectVariables(expression, affectedVariables);
        return Set.of(new InferenceCandidate(
            kind,
            subjectExpression,
            AssumptionSignature.normalizeExpression(subjectExpression + " " + relation),
            Set.copyOf(affectedVariables)
        ));
    }

    private CounterexampleSearchService.TypedAssumption typedAssumption(InferenceCandidate candidate, String source) {
        return new CounterexampleSearchService.TypedAssumption(
            candidate.kind(),
            candidate.normalizedPredicate(),
            candidate.subjectExpression(),
            List.copyOf(candidate.affectedVariables()),
            source == null || source.isBlank() ? List.of() : List.of(source),
            proofEncodings(candidate),
            CounterexampleSearchService.AssumptionClassification.UNKNOWN
        );
    }

    private CounterexampleSearchService.TypedAssumption mergeTypedAssumptions(
        CounterexampleSearchService.TypedAssumption left,
        CounterexampleSearchService.TypedAssumption right
    ) {
        LinkedHashSet<String> variables = new LinkedHashSet<>(left.affectedVariables());
        variables.addAll(right.affectedVariables());
        LinkedHashSet<String> evidenceSources = new LinkedHashSet<>(left.evidenceSources());
        evidenceSources.addAll(right.evidenceSources());
        LinkedHashMap<String, CounterexampleSearchService.ProofEncoding> proofEncodings = new LinkedHashMap<>();
        for (CounterexampleSearchService.ProofEncoding encoding : left.proofEncodings()) {
            proofEncodings.put(encoding.dialect(), encoding);
        }
        for (CounterexampleSearchService.ProofEncoding encoding : right.proofEncodings()) {
            proofEncodings.put(encoding.dialect(), encoding);
        }
        return new CounterexampleSearchService.TypedAssumption(
            left.kind(),
            left.normalizedPredicate(),
            left.subjectExpression(),
            List.copyOf(variables),
            List.copyOf(evidenceSources),
            List.copyOf(proofEncodings.values()),
            left.classification()
        );
    }

    private List<CounterexampleSearchService.ProofEncoding> proofEncodings(InferenceCandidate candidate) {
        List<CounterexampleSearchService.ProofEncoding> encodings = new ArrayList<>();
        String leanRelation = candidate.normalizedPredicate().replace("!=", "≠");
        encodings.add(new CounterexampleSearchService.ProofEncoding("lean", leanRelation));
        try {
            Expr subject = parser.parse(new InputRequest(InputType.TERM, candidate.subjectExpression())).terms().getFirst();
            String smtSubject = toSmt(subject);
            if (!smtSubject.isBlank()) {
                String smt = switch (candidate.kind()) {
                    case NON_ZERO, INVERTIBLE -> "(not (= " + smtSubject + " 0))";
                    case POSITIVE -> "(> " + smtSubject + " 0)";
                    case NON_NEGATIVE -> "(>= " + smtSubject + " 0)";
                    default -> "";
                };
                if (!smt.isBlank()) {
                    encodings.add(new CounterexampleSearchService.ProofEncoding("smtlib", smt));
                }
            }
        } catch (RuntimeException ignored) {
            // Best-effort proof encodings only.
        }
        return List.copyOf(encodings);
    }

    private String toSmt(Expr expression) {
        if (expression instanceof NumberExpr numberExpr) {
            return trimDouble(numberExpr.value());
        }
        if (expression instanceof VariableExpr variableExpr) {
            return variableExpr.name();
        }
        if (expression instanceof FunctionExpr functionExpr && functionExpr.arguments().size() == 1) {
            return "(" + functionExpr.name() + " " + toSmt(functionExpr.arguments().getFirst()) + ")";
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            String operator = switch (binaryExpr.operator()) {
                case ADD -> "+";
                case SUB -> "-";
                case MUL -> "*";
                case DIV -> "/";
                case POW -> "pow";
            };
            return "(" + operator + " " + toSmt(binaryExpr.left()) + " " + toSmt(binaryExpr.right()) + ")";
        }
        return "";
    }

    private List<String> inferredAssumptionExpressions(Map<String, CounterexampleSearchService.TypedAssumption> assumptions) {
        return assumptions.values().stream()
            .map(CounterexampleSearchService.TypedAssumption::normalizedPredicate)
            .toList();
    }

    private List<CounterexampleSearchService.TypedAssumption> typedAssumptions(
        Map<String, CounterexampleSearchService.TypedAssumption> assumptions
    ) {
        return List.copyOf(assumptions.values());
    }

    private static String trimDouble(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private record Evaluation(
        RuntimeValue value,
        boolean defined,
        Set<InferenceCandidate> inferredAssumptions
    ) {
        private Evaluation {
            inferredAssumptions = inferredAssumptions == null ? Set.of() : Set.copyOf(inferredAssumptions);
        }

        static Evaluation defined(RuntimeValue value) {
            return new Evaluation(value, true, Set.of());
        }

        static Evaluation undefined(Set<InferenceCandidate> inferredAssumptions) {
            return new Evaluation(RuntimeValue.scalar(Double.NaN), false, inferredAssumptions);
        }
    }

    private record InferenceCandidate(
        CounterexampleSearchService.AssumptionKind kind,
        String subjectExpression,
        String normalizedPredicate,
        Set<String> affectedVariables
    ) {
    }

    private enum Relation {
        NON_ZERO,
        POSITIVE,
        NON_NEGATIVE
    }

    private record AssumptionGuard(String symbol, Relation relation) {
    }

    private record RuntimeValue(Kind kind, double scalar, Matrix matrix, Complex complex) {
        enum Kind {
            SCALAR,
            MATRIX,
            COMPLEX
        }

        static RuntimeValue scalar(double value) {
            return new RuntimeValue(Kind.SCALAR, value, null, null);
        }

        static RuntimeValue matrix(Matrix value) {
            return new RuntimeValue(Kind.MATRIX, 0.0, value, null);
        }

        static RuntimeValue complex(Complex value) {
            return new RuntimeValue(Kind.COMPLEX, 0.0, null, value);
        }

        boolean isNumeric() {
            return kind == Kind.SCALAR || kind == Kind.COMPLEX;
        }

        Complex asComplex() {
            return kind == Kind.COMPLEX ? complex : new Complex(scalar, 0.0);
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
            if (kind == Kind.COMPLEX) {
                return complex.subtract(other.complex).abs() <= EPS;
            }
            return matrix.equals(other.matrix);
        }

        String asText() {
            if (kind == Kind.SCALAR) {
                return trimDouble(scalar);
            }
            if (kind == Kind.COMPLEX) {
                return complex.asText();
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

    private record Complex(double real, double imaginary) {
        Complex add(Complex other) {
            return new Complex(real + other.real, imaginary + other.imaginary);
        }

        Complex subtract(Complex other) {
            return new Complex(real - other.real, imaginary - other.imaginary);
        }

        Complex multiply(Complex other) {
            return new Complex(
                real * other.real - imaginary * other.imaginary,
                real * other.imaginary + imaginary * other.real
            );
        }

        Complex divide(Complex other) {
            double denominator = other.real * other.real + other.imaginary * other.imaginary;
            return new Complex(
                (real * other.real + imaginary * other.imaginary) / denominator,
                (imaginary * other.real - real * other.imaginary) / denominator
            );
        }

        Complex pow(int exponent) {
            if (exponent == 0) {
                return new Complex(1.0, 0.0);
            }
            Complex result = new Complex(1.0, 0.0);
            int count = Math.abs(exponent);
            for (int i = 0; i < count; i++) {
                result = result.multiply(this);
            }
            return exponent < 0 ? new Complex(1.0, 0.0).divide(result) : result;
        }

        Complex sqrt() {
            double magnitude = abs();
            double realPart = Math.sqrt((magnitude + real) / 2.0);
            double imaginaryPart = Math.copySign(Math.sqrt((magnitude - real) / 2.0), imaginary);
            return new Complex(realPart, imaginaryPart);
        }

        double abs() {
            return Math.hypot(real, imaginary);
        }

        String asText() {
            if (Math.abs(imaginary) <= EPS) {
                return trimDouble(real);
            }
            if (Math.abs(real) <= EPS) {
                return trimDouble(imaginary) + "i";
            }
            return trimDouble(real) + (imaginary < 0 ? "" : "+") + trimDouble(imaginary) + "i";
        }
    }
}
