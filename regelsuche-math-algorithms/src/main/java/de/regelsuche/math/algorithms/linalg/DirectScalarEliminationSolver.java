package de.regelsuche.math.algorithms.linalg;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.equivalence.Rational;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.ExactVector;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem.SolutionClassification;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.representation.RepresentationBridge.WorkLedger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Exact equation-level substitution/elimination baseline for matched-work
 * comparison with the matrix-representation route.
 *
 * <p>The implementation deliberately does not construct an
 * {@link ExactLinearSystem} or an {@link ExactRrefReduction}. It retains each
 * scalar equation as a sparse named-coefficient map, chooses pivot variables in
 * the declared order and substitutes solved equations into the remaining
 * scalar equations. It nevertheless has the same terminal obligation: a
 * canonical exact solution consequence or a normalized contradiction.</p>
 */
public final class DirectScalarEliminationSolver {
    public static final String SOLVER_ID =
        "direct-scalar-substitution-elimination/v1";
    public static final String CERTIFICATE_SCHEMA =
        "regelsuche.direct-scalar-elimination-certificate/v1";
    private static final int MAX_ABSOLUTE_CONSTANT_EXPONENT = 64;

    public Result solve(Source source, Budget budget) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(budget, "budget");
        WorkCounter work = new WorkCounter(budget.maxWorkUnits());
        try {
            validateDeclaredVariables(source, work);
            List<ScalarEquation> original = extract(source, work);
            List<ScalarEquation> reduced = mutableCopy(original);
            List<EquationOperation> operations = new ArrayList<>();
            List<Pivot> pivots = eliminate(
                reduced,
                source.variables(),
                operations,
                work);

            boolean inconsistent = firstContradiction(reduced, work) >= 0;
            List<String> freeVariables = freeVariables(
                source.variables(),
                pivots);
            ExactLinearSolutionConsequence consequence = consequence(
                source.variables(),
                reduced,
                pivots,
                freeVariables,
                inconsistent,
                work);

            if (!replayMatches(original, reduced, operations, work)
                    || !consequenceMatchesOriginal(
                        original,
                        source.variables(),
                        consequence,
                        work)) {
                return Result.withoutConsequence(
                    Status.INVALID_CERTIFICATE,
                    work.profile(),
                    work.ledger(),
                    "DIRECT_SCALAR_REPLAY_OR_CONSEQUENCE_MISMATCH");
            }

            Certificate certificate = certificate(
                source,
                reduced,
                operations,
                consequence,
                work.profile());
            return Result.solved(
                consequence,
                certificate,
                work.profile(),
                work.ledger());
        } catch (BudgetExceeded exception) {
            return Result.withoutConsequence(
                Status.BUDGET_INCONCLUSIVE,
                work.profile(),
                work.ledger(),
                "DIRECT_SCALAR_WORK_BUDGET_EXHAUSTED");
        } catch (NonlinearExpression exception) {
            return Result.withoutConsequence(
                Status.NONLINEAR,
                work.profile(),
                work.ledger(),
                exception.detailCode());
        } catch (UnsupportedExpression exception) {
            return Result.withoutConsequence(
                Status.DOMAIN_UNSUPPORTED,
                work.profile(),
                work.ledger(),
                exception.detailCode());
        }
    }

    public boolean verify(Source source, Result result) {
        if (source == null
                || result == null
                || result.status() != Status.SOLVED) {
            return false;
        }
        try {
            return solve(
                source,
                new Budget(result.work().configuredWorkUnits()))
                .equals(result);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void validateDeclaredVariables(
        Source source,
        WorkCounter work
    ) {
        Set<String> discovered = new TreeSet<>();
        for (Equation equation : source.equations()) {
            collectVariables(equation.left(), discovered, work);
            collectVariables(equation.right(), discovered, work);
        }
        if (!List.copyOf(discovered).equals(source.variables())) {
            throw new UnsupportedExpression(
                "DECLARED_VARIABLE_ORDER_OR_SET_MISMATCH");
        }
    }

    private static void collectVariables(
        Expr expression,
        Set<String> target,
        WorkCounter work
    ) {
        work.consume(Stage.SOURCE_ANALYSIS);
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

    private static List<ScalarEquation> extract(
        Source source,
        WorkCounter work
    ) {
        Set<String> variables = Set.copyOf(source.variables());
        List<ScalarEquation> equations = new ArrayList<>(
            source.equations().size());
        for (Equation equation : source.equations()) {
            AffineForm left = affine(
                equation.left(),
                source.variables(),
                variables,
                work);
            AffineForm right = affine(
                equation.right(),
                source.variables(),
                variables,
                work);
            AffineForm normalized = subtract(
                left,
                right,
                source.variables(),
                work);
            work.consume(Stage.SOURCE_ANALYSIS);
            equations.add(new ScalarEquation(
                normalized.coefficients(),
                normalized.constant().negate()));
        }
        return List.copyOf(equations);
    }

    private static AffineForm affine(
        Expr expression,
        List<String> variableOrder,
        Set<String> variables,
        WorkCounter work
    ) {
        work.consume(Stage.SOURCE_ANALYSIS);
        if (expression instanceof NumberExpr number) {
            if (!Double.isFinite(number.value())) {
                throw new UnsupportedExpression("NON_FINITE_NUMBER");
            }
            return AffineForm.constant(Rational.fromDouble(number.value()));
        }
        if (expression instanceof VariableExpr variable) {
            if (!variables.contains(variable.name())) {
                throw new UnsupportedExpression(
                    "UNDECLARED_SYMBOL_IN_DIRECT_NUMERIC_SYSTEM");
            }
            return AffineForm.variable(variable.name());
        }
        if (expression instanceof FunctionExpr) {
            throw new UnsupportedExpression(
                "FUNCTION_OUTSIDE_DIRECT_AFFINE_FRAGMENT");
        }
        if (!(expression instanceof BinaryExpr binary)) {
            throw new UnsupportedExpression("UNKNOWN_EXPRESSION_NODE");
        }

        if (binary.operator() == BinaryOperator.POW) {
            AffineForm base = affine(
                binary.left(),
                variableOrder,
                variables,
                work);
            AffineForm exponent = affine(
                binary.right(),
                variableOrder,
                variables,
                work);
            return power(base, exponent, work);
        }

        AffineForm left = affine(
            binary.left(),
            variableOrder,
            variables,
            work);
        AffineForm right = affine(
            binary.right(),
            variableOrder,
            variables,
            work);
        return switch (binary.operator()) {
            case ADD -> add(left, right, variableOrder, work);
            case SUB -> subtract(left, right, variableOrder, work);
            case MUL -> multiply(left, right, variableOrder, work);
            case DIV -> divide(left, right, variableOrder, work);
            case POW -> throw new IllegalStateException(
                "power handled before recursive binary switch");
        };
    }

    private static AffineForm power(
        AffineForm base,
        AffineForm exponent,
        WorkCounter work
    ) {
        if (!exponent.isConstant()
                || !exponent.constant().denominator().equals(
                    java.math.BigInteger.ONE)) {
            throw new NonlinearExpression(
                "NON_CONSTANT_OR_NON_INTEGER_DIRECT_EXPONENT");
        }
        java.math.BigInteger exactExponent =
            exponent.constant().numerator();
        if (exactExponent.abs().compareTo(java.math.BigInteger.valueOf(
                MAX_ABSOLUTE_CONSTANT_EXPONENT)) > 0) {
            throw new UnsupportedExpression(
                "EXPONENT_OUTSIDE_DIRECT_AFFINE_FRAGMENT");
        }
        int exponentValue = exactExponent.intValueExact();
        if (exponentValue == 1) {
            return base;
        }
        if (!base.isConstant()) {
            throw new NonlinearExpression(
                "NONLINEAR_POWER_IN_DIRECT_SCALAR_SYSTEM");
        }
        if (base.constant().isZero() && exponentValue <= 0) {
            throw new UnsupportedExpression("UNDEFINED_ZERO_POWER");
        }
        return AffineForm.constant(pow(base.constant(), exponentValue, work));
    }

    private static Rational pow(
        Rational base,
        int exponent,
        WorkCounter work
    ) {
        if (exponent == 0) {
            return Rational.ONE;
        }
        boolean reciprocal = exponent < 0;
        int remaining = Math.abs(exponent);
        Rational result = Rational.ONE;
        Rational factor = base;
        while (remaining > 0) {
            if ((remaining & 1) == 1) {
                work.consume(Stage.SOURCE_ANALYSIS);
                result = result.multiply(factor);
            }
            remaining >>>= 1;
            if (remaining > 0) {
                work.consume(Stage.SOURCE_ANALYSIS);
                factor = factor.multiply(factor);
            }
        }
        if (!reciprocal) {
            return result;
        }
        work.consume(Stage.SOURCE_ANALYSIS);
        return Rational.ONE.divide(result);
    }

    private static AffineForm add(
        AffineForm left,
        AffineForm right,
        List<String> variables,
        WorkCounter work
    ) {
        Map<String, Rational> coefficients = new LinkedHashMap<>();
        for (String variable : variables) {
            work.consume(Stage.SOURCE_ANALYSIS);
            Rational sum = left.coefficient(variable)
                .add(right.coefficient(variable));
            if (!sum.isZero()) {
                coefficients.put(variable, sum);
            }
        }
        work.consume(Stage.SOURCE_ANALYSIS);
        return new AffineForm(
            coefficients,
            left.constant().add(right.constant()));
    }

    private static AffineForm subtract(
        AffineForm left,
        AffineForm right,
        List<String> variables,
        WorkCounter work
    ) {
        Map<String, Rational> coefficients = new LinkedHashMap<>();
        for (String variable : variables) {
            work.consume(Stage.SOURCE_ANALYSIS);
            Rational difference = left.coefficient(variable)
                .subtract(right.coefficient(variable));
            if (!difference.isZero()) {
                coefficients.put(variable, difference);
            }
        }
        work.consume(Stage.SOURCE_ANALYSIS);
        return new AffineForm(
            coefficients,
            left.constant().subtract(right.constant()));
    }

    private static AffineForm multiply(
        AffineForm left,
        AffineForm right,
        List<String> variables,
        WorkCounter work
    ) {
        if (!left.isConstant() && !right.isConstant()) {
            throw new NonlinearExpression(
                "PRODUCT_OF_NON_CONSTANT_DIRECT_FORMS");
        }
        if (left.isConstant()) {
            return scale(right, left.constant(), variables, work);
        }
        return scale(left, right.constant(), variables, work);
    }

    private static AffineForm divide(
        AffineForm numerator,
        AffineForm denominator,
        List<String> variables,
        WorkCounter work
    ) {
        if (!denominator.isConstant()) {
            throw new UnsupportedExpression(
                "VARIABLE_DENOMINATOR_IN_DIRECT_SCALAR_SYSTEM");
        }
        if (denominator.constant().isZero()) {
            throw new UnsupportedExpression("EXPLICIT_DIVISION_BY_ZERO");
        }
        work.consume(Stage.SOURCE_ANALYSIS);
        return scale(
            numerator,
            Rational.ONE.divide(denominator.constant()),
            variables,
            work);
    }

    private static AffineForm scale(
        AffineForm form,
        Rational scalar,
        List<String> variables,
        WorkCounter work
    ) {
        if (scalar.isZero()) {
            return AffineForm.constant(Rational.ZERO);
        }
        Map<String, Rational> coefficients = new LinkedHashMap<>();
        for (String variable : variables) {
            Rational coefficient = form.coefficient(variable);
            if (coefficient.isZero()) {
                continue;
            }
            work.consume(Stage.SOURCE_ANALYSIS);
            Rational scaled = coefficient.multiply(scalar);
            if (!scaled.isZero()) {
                coefficients.put(variable, scaled);
            }
        }
        work.consume(Stage.SOURCE_ANALYSIS);
        return new AffineForm(
            coefficients,
            form.constant().multiply(scalar));
    }

    private static List<Pivot> eliminate(
        List<ScalarEquation> equations,
        List<String> variables,
        List<EquationOperation> operations,
        WorkCounter work
    ) {
        List<Pivot> pivots = new ArrayList<>();
        int pivotRow = 0;
        for (String variable : variables) {
            if (pivotRow >= equations.size()) {
                break;
            }
            int candidate = findPivot(
                equations,
                pivotRow,
                variable,
                work);
            if (candidate < 0) {
                continue;
            }
            if (candidate != pivotRow) {
                work.consume(Stage.ELIMINATION, equations.size());
                Collections.swap(equations, candidate, pivotRow);
                operations.add(EquationOperation.swap(pivotRow, candidate));
            }

            Rational pivot = coefficient(
                equations.get(pivotRow),
                variable,
                Stage.ELIMINATION,
                work);
            if (!pivot.isOne()) {
                work.consume(Stage.ELIMINATION);
                Rational multiplier = Rational.ONE.divide(pivot);
                equations.set(
                    pivotRow,
                    scaleEquation(
                        equations.get(pivotRow),
                        multiplier,
                        Stage.ELIMINATION,
                        work));
                operations.add(EquationOperation.scale(
                    pivotRow,
                    multiplier));
            }

            for (int row = 0; row < equations.size(); row++) {
                if (row == pivotRow) {
                    continue;
                }
                Rational factor = coefficient(
                    equations.get(row),
                    variable,
                    Stage.ELIMINATION,
                    work);
                if (factor.isZero()) {
                    continue;
                }
                Rational multiplier = factor.negate();
                equations.set(
                    row,
                    addEquationMultiple(
                        equations.get(row),
                        equations.get(pivotRow),
                        multiplier,
                        Stage.ELIMINATION,
                        work));
                operations.add(EquationOperation.addMultiple(
                    row,
                    pivotRow,
                    multiplier));
            }
            pivots.add(new Pivot(pivotRow, variable));
            pivotRow++;
        }
        return List.copyOf(pivots);
    }

    private static int findPivot(
        List<ScalarEquation> equations,
        int firstRow,
        String variable,
        WorkCounter work
    ) {
        for (int row = firstRow; row < equations.size(); row++) {
            if (!coefficient(
                    equations.get(row),
                    variable,
                    Stage.ELIMINATION,
                    work).isZero()) {
                return row;
            }
        }
        return -1;
    }

    private static Rational coefficient(
        ScalarEquation equation,
        String variable,
        Stage stage,
        WorkCounter work
    ) {
        work.consume(stage);
        return equation.coefficients().getOrDefault(
            variable,
            Rational.ZERO);
    }

    private static ScalarEquation scaleEquation(
        ScalarEquation equation,
        Rational multiplier,
        Stage stage,
        WorkCounter work
    ) {
        Map<String, Rational> coefficients = new LinkedHashMap<>();
        for (Map.Entry<String, Rational> entry
                : equation.coefficients().entrySet()) {
            work.consume(stage);
            Rational scaled = entry.getValue().multiply(multiplier);
            if (!scaled.isZero()) {
                coefficients.put(entry.getKey(), scaled);
            }
        }
        work.consume(stage);
        return new ScalarEquation(
            coefficients,
            equation.rightHandSide().multiply(multiplier));
    }

    private static ScalarEquation addEquationMultiple(
        ScalarEquation target,
        ScalarEquation source,
        Rational multiplier,
        Stage stage,
        WorkCounter work
    ) {
        Map<String, Rational> coefficients = new LinkedHashMap<>(
            target.coefficients());
        for (Map.Entry<String, Rational> entry
                : source.coefficients().entrySet()) {
            work.consume(stage);
            Rational replacement = coefficients
                .getOrDefault(entry.getKey(), Rational.ZERO)
                .add(entry.getValue().multiply(multiplier));
            if (replacement.isZero()) {
                coefficients.remove(entry.getKey());
            } else {
                coefficients.put(entry.getKey(), replacement);
            }
        }
        work.consume(stage);
        Rational rightHandSide = target.rightHandSide().add(
            source.rightHandSide().multiply(multiplier));
        return new ScalarEquation(coefficients, rightHandSide);
    }

    private static int firstContradiction(
        List<ScalarEquation> equations,
        WorkCounter work
    ) {
        for (int row = 0; row < equations.size(); row++) {
            work.consume(Stage.ELIMINATION);
            if (equations.get(row).coefficients().isEmpty()) {
                work.consume(Stage.ELIMINATION);
                if (!equations.get(row).rightHandSide().isZero()) {
                    return row;
                }
            }
        }
        return -1;
    }

    private static List<String> freeVariables(
        List<String> variables,
        List<Pivot> pivots
    ) {
        Set<String> pivotVariables = new HashSet<>();
        pivots.forEach(pivot -> pivotVariables.add(pivot.variable()));
        return variables.stream()
            .filter(variable -> !pivotVariables.contains(variable))
            .toList();
    }

    private static ExactLinearSolutionConsequence consequence(
        List<String> variables,
        List<ScalarEquation> reduced,
        List<Pivot> pivots,
        List<String> freeVariables,
        boolean inconsistent,
        WorkCounter work
    ) {
        if (inconsistent) {
            return new ExactLinearSolutionConsequence(
                variables,
                SolutionClassification.INCONSISTENT,
                Optional.empty(),
                List.of(),
                Optional.of(Rational.ONE));
        }

        Map<String, Integer> indices = new LinkedHashMap<>();
        for (int index = 0; index < variables.size(); index++) {
            indices.put(variables.get(index), index);
        }
        List<Rational> particular = new ArrayList<>(
            Collections.nCopies(variables.size(), Rational.ZERO));
        for (Pivot pivot : pivots) {
            work.consume(Stage.EVIDENCE);
            particular.set(
                indices.get(pivot.variable()),
                reduced.get(pivot.row()).rightHandSide());
        }

        List<ExactVector> basis = new ArrayList<>(freeVariables.size());
        for (String freeVariable : freeVariables) {
            List<Rational> vector = new ArrayList<>(
                Collections.nCopies(variables.size(), Rational.ZERO));
            vector.set(indices.get(freeVariable), Rational.ONE);
            for (Pivot pivot : pivots) {
                Rational freeCoefficient = coefficient(
                    reduced.get(pivot.row()),
                    freeVariable,
                    Stage.EVIDENCE,
                    work);
                vector.set(
                    indices.get(pivot.variable()),
                    freeCoefficient.negate());
            }
            basis.add(new ExactVector(vector));
        }

        SolutionClassification classification = freeVariables.isEmpty()
            ? SolutionClassification.UNIQUE
            : SolutionClassification.UNDERDETERMINED;
        return new ExactLinearSolutionConsequence(
            variables,
            classification,
            Optional.of(new ExactVector(particular)),
            basis,
            Optional.empty());
    }

    private static boolean replayMatches(
        List<ScalarEquation> original,
        List<ScalarEquation> expected,
        List<EquationOperation> operations,
        WorkCounter work
    ) {
        List<ScalarEquation> replay = mutableCopy(original);
        for (EquationOperation operation : operations) {
            switch (operation.kind()) {
                case SWAP_EQUATIONS -> {
                    work.consume(Stage.EVIDENCE, replay.size());
                    Collections.swap(
                        replay,
                        operation.targetEquation(),
                        operation.sourceEquation());
                }
                case SCALE_EQUATION -> replay.set(
                    operation.targetEquation(),
                    scaleEquation(
                        replay.get(operation.targetEquation()),
                        operation.multiplier(),
                        Stage.EVIDENCE,
                        work));
                case ADD_EQUATION_MULTIPLE -> replay.set(
                    operation.targetEquation(),
                    addEquationMultiple(
                        replay.get(operation.targetEquation()),
                        replay.get(operation.sourceEquation()),
                        operation.multiplier(),
                        Stage.EVIDENCE,
                        work));
            }
        }
        if (replay.size() != expected.size()) {
            return false;
        }
        for (int row = 0; row < replay.size(); row++) {
            work.consume(Stage.EVIDENCE);
            if (!replay.get(row).equals(expected.get(row))) {
                return false;
            }
        }
        return true;
    }

    private static boolean consequenceMatchesOriginal(
        List<ScalarEquation> original,
        List<String> variables,
        ExactLinearSolutionConsequence consequence,
        WorkCounter work
    ) {
        if (consequence.classification()
                == SolutionClassification.INCONSISTENT) {
            return consequence.normalizedContradiction()
                .filter(Rational::isOne)
                .isPresent();
        }
        ExactVector particular = consequence.particularSolution()
            .orElseThrow();
        if (!satisfies(original, variables, particular, false, work)) {
            return false;
        }
        for (ExactVector basisVector : consequence.nullspaceBasis()) {
            if (!satisfies(original, variables, basisVector, true, work)) {
                return false;
            }
        }
        return true;
    }

    private static boolean satisfies(
        List<ScalarEquation> equations,
        List<String> variables,
        ExactVector vector,
        boolean homogeneous,
        WorkCounter work
    ) {
        Map<String, Integer> indices = new LinkedHashMap<>();
        for (int index = 0; index < variables.size(); index++) {
            indices.put(variables.get(index), index);
        }
        for (ScalarEquation equation : equations) {
            Rational sum = Rational.ZERO;
            for (Map.Entry<String, Rational> entry
                    : equation.coefficients().entrySet()) {
                work.consume(Stage.EVIDENCE);
                sum = sum.add(entry.getValue().multiply(
                    vector.get(indices.get(entry.getKey()))));
            }
            work.consume(Stage.EVIDENCE);
            Rational expected = homogeneous
                ? Rational.ZERO
                : equation.rightHandSide();
            if (!sum.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private static List<ScalarEquation> mutableCopy(
        List<ScalarEquation> source
    ) {
        return new ArrayList<>(source.stream()
            .map(ScalarEquation::copy)
            .toList());
    }

    private static Certificate certificate(
        Source source,
        List<ScalarEquation> reduced,
        List<EquationOperation> operations,
        ExactLinearSolutionConsequence consequence,
        StageWorkProfile profile
    ) {
        List<String> sourceEquations = source.equations().stream()
            .map(ExpressionFormatter::format)
            .toList();
        List<String> reducedEquations = reduced.stream()
            .map(equation -> equation.canonical(source.variables()))
            .toList();
        List<String> canonicalOperations = operations.stream()
            .map(EquationOperation::canonical)
            .toList();
        List<String> consequenceLines = consequence.canonicalLines();
        String sourceHash = sourceHash(sourceEquations, source.variables());

        StringBuilder payload = new StringBuilder();
        append(payload, CERTIFICATE_SCHEMA);
        append(payload, SOLVER_ID);
        append(payload, sourceHash);
        sourceEquations.forEach(value -> append(payload, value));
        source.variables().forEach(value -> append(payload, value));
        reducedEquations.forEach(value -> append(payload, value));
        canonicalOperations.forEach(value -> append(payload, value));
        consequenceLines.forEach(value -> append(payload, value));
        append(payload, Integer.toString(profile.sourceAnalysisWork()));
        append(payload, Integer.toString(profile.eliminationWork()));
        append(payload, Integer.toString(profile.evidenceWork()));

        return new Certificate(
            CERTIFICATE_SCHEMA,
            SOLVER_ID,
            sourceHash,
            sourceEquations,
            source.variables(),
            reducedEquations,
            canonicalOperations,
            consequenceLines,
            profile,
            sha256(payload.toString()));
    }

    private static String sourceHash(
        List<String> equations,
        List<String> variables
    ) {
        StringBuilder payload = new StringBuilder();
        equations.forEach(value -> append(payload, value));
        variables.forEach(value -> append(payload, value));
        return sha256(payload.toString());
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

    public enum Status {
        SOLVED,
        NONLINEAR,
        DOMAIN_UNSUPPORTED,
        BUDGET_INCONCLUSIVE,
        INVALID_CERTIFICATE
    }

    public enum Stage {
        SOURCE_ANALYSIS,
        ELIMINATION,
        EVIDENCE
    }

    public record StageWorkProfile(
        int sourceAnalysisWork,
        int eliminationWork,
        int evidenceWork
    ) {
        public StageWorkProfile {
            if (sourceAnalysisWork < 0
                    || eliminationWork < 0
                    || evidenceWork < 0) {
                throw new IllegalArgumentException(
                    "stage work must not be negative");
            }
        }

        public int totalWork() {
            return Math.addExact(
                sourceAnalysisWork,
                Math.addExact(eliminationWork, evidenceWork));
        }
    }

    public record Source(
        List<Equation> equations,
        List<String> variables
    ) {
        public Source {
            equations = List.copyOf(Objects.requireNonNull(
                equations,
                "equations"));
            variables = normalizedVariables(variables);
            if (equations.isEmpty()) {
                throw new IllegalArgumentException(
                    "direct scalar system requires at least one equation");
            }
        }

        private static List<String> normalizedVariables(List<String> values) {
            Objects.requireNonNull(values, "variables");
            if (values.isEmpty()) {
                throw new IllegalArgumentException(
                    "direct scalar system requires declared variables");
            }
            List<String> normalized = values.stream().map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        "variable names must not be blank");
                }
                return value.trim();
            }).toList();
            if (new HashSet<>(normalized).size() != normalized.size()) {
                throw new IllegalArgumentException(
                    "variable names must be unique");
            }
            return normalized;
        }
    }

    public record Certificate(
        String schema,
        String solverId,
        String sourceHash,
        List<String> sourceEquations,
        List<String> variables,
        List<String> reducedEquations,
        List<String> canonicalOperations,
        List<String> consequenceLines,
        StageWorkProfile workProfile,
        String contentHash
    ) {
        public Certificate {
            if (!CERTIFICATE_SCHEMA.equals(schema)
                    || !SOLVER_ID.equals(solverId)
                    || sourceHash == null
                    || !sourceHash.matches("[0-9a-f]{64}")
                    || contentHash == null
                    || !contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                    "direct scalar certificate identities are invalid");
            }
            sourceEquations = nonEmptyTexts(
                sourceEquations,
                "sourceEquations");
            variables = nonEmptyTexts(variables, "variables");
            reducedEquations = nonEmptyTexts(
                reducedEquations,
                "reducedEquations");
            canonicalOperations = textsAllowEmpty(
                canonicalOperations,
                "canonicalOperations");
            consequenceLines = nonEmptyTexts(
                consequenceLines,
                "consequenceLines");
            workProfile = Objects.requireNonNull(
                workProfile,
                "workProfile");
        }

        private static List<String> nonEmptyTexts(
            List<String> values,
            String field
        ) {
            List<String> retained = textsAllowEmpty(values, field);
            if (retained.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be empty");
            }
            return retained;
        }

        private static List<String> textsAllowEmpty(
            List<String> values,
            String field
        ) {
            Objects.requireNonNull(values, field);
            return values.stream().map(value -> {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException(
                        field + " entries must not be blank");
                }
                return value.trim();
            }).toList();
        }
    }

    public record Result(
        Status status,
        Optional<ExactLinearSolutionConsequence> consequence,
        Optional<Certificate> certificate,
        StageWorkProfile workProfile,
        WorkLedger work,
        String detailCode
    ) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            consequence = Objects.requireNonNull(consequence, "consequence");
            certificate = Objects.requireNonNull(certificate, "certificate");
            workProfile = Objects.requireNonNull(
                workProfile,
                "workProfile");
            work = Objects.requireNonNull(work, "work");
            if (detailCode == null || detailCode.isBlank()) {
                throw new IllegalArgumentException(
                    "detailCode must not be blank");
            }
            detailCode = detailCode.trim();
            if (workProfile.totalWork() != work.consumedWorkUnits()) {
                throw new IllegalArgumentException(
                    "stage profile and total work disagree");
            }
            boolean payload = consequence.isPresent() && certificate.isPresent();
            if ((status == Status.SOLVED) != payload
                    || consequence.isPresent() != certificate.isPresent()) {
                throw new IllegalArgumentException(
                    "only solved direct results retain complete evidence");
            }
        }

        private static Result solved(
            ExactLinearSolutionConsequence consequence,
            Certificate certificate,
            StageWorkProfile profile,
            WorkLedger work
        ) {
            return new Result(
                Status.SOLVED,
                Optional.of(consequence),
                Optional.of(certificate),
                profile,
                work,
                "DIRECT_SCALAR_CONSEQUENCE_COMPUTED");
        }

        private static Result withoutConsequence(
            Status status,
            StageWorkProfile profile,
            WorkLedger work,
            String detailCode
        ) {
            if (status == Status.SOLVED) {
                throw new IllegalArgumentException(
                    "solved result requires direct consequence evidence");
            }
            return new Result(
                status,
                Optional.empty(),
                Optional.empty(),
                profile,
                work,
                detailCode);
        }
    }

    private record AffineForm(
        Map<String, Rational> coefficients,
        Rational constant
    ) {
        private AffineForm {
            Objects.requireNonNull(coefficients, "coefficients");
            LinkedHashMap<String, Rational> retained = new LinkedHashMap<>();
            coefficients.forEach((variable, coefficient) -> {
                if (variable == null || variable.isBlank()) {
                    throw new IllegalArgumentException(
                        "coefficient variable must not be blank");
                }
                Rational value = Objects.requireNonNull(
                    coefficient,
                    "coefficient");
                if (!value.isZero()) {
                    retained.put(variable.trim(), value);
                }
            });
            coefficients = Collections.unmodifiableMap(retained);
            constant = Objects.requireNonNull(constant, "constant");
        }

        private static AffineForm constant(Rational value) {
            return new AffineForm(Map.of(), value);
        }

        private static AffineForm variable(String variable) {
            return new AffineForm(Map.of(variable, Rational.ONE), Rational.ZERO);
        }

        private Rational coefficient(String variable) {
            return coefficients.getOrDefault(variable, Rational.ZERO);
        }

        private boolean isConstant() {
            return coefficients.isEmpty();
        }
    }

    private record ScalarEquation(
        Map<String, Rational> coefficients,
        Rational rightHandSide
    ) {
        private ScalarEquation {
            Objects.requireNonNull(coefficients, "coefficients");
            LinkedHashMap<String, Rational> retained = new LinkedHashMap<>();
            coefficients.forEach((variable, coefficient) -> {
                Rational value = Objects.requireNonNull(
                    coefficient,
                    "coefficient");
                if (!value.isZero()) {
                    retained.put(variable, value);
                }
            });
            coefficients = Collections.unmodifiableMap(retained);
            rightHandSide = Objects.requireNonNull(
                rightHandSide,
                "rightHandSide");
        }

        private ScalarEquation copy() {
            return new ScalarEquation(coefficients, rightHandSide);
        }

        private String canonical(List<String> variableOrder) {
            List<String> terms = new ArrayList<>();
            for (String variable : variableOrder) {
                Rational coefficient = coefficients.get(variable);
                if (coefficient != null) {
                    terms.add(coefficient + "*" + variable);
                }
            }
            return (terms.isEmpty() ? "0" : String.join("+", terms))
                + "=" + rightHandSide;
        }
    }

    private record Pivot(int row, String variable) {
        private Pivot {
            if (row < 0 || variable == null || variable.isBlank()) {
                throw new IllegalArgumentException("pivot is invalid");
            }
            variable = variable.trim();
        }
    }

    private enum OperationKind {
        SWAP_EQUATIONS,
        SCALE_EQUATION,
        ADD_EQUATION_MULTIPLE
    }

    private record EquationOperation(
        OperationKind kind,
        int targetEquation,
        int sourceEquation,
        Rational multiplier
    ) {
        private EquationOperation {
            kind = Objects.requireNonNull(kind, "kind");
            multiplier = Objects.requireNonNull(multiplier, "multiplier");
            if (targetEquation < 0) {
                throw new IllegalArgumentException(
                    "targetEquation must not be negative");
            }
        }

        private static EquationOperation swap(int first, int second) {
            return new EquationOperation(
                OperationKind.SWAP_EQUATIONS,
                first,
                second,
                Rational.ONE);
        }

        private static EquationOperation scale(int row, Rational multiplier) {
            return new EquationOperation(
                OperationKind.SCALE_EQUATION,
                row,
                -1,
                multiplier);
        }

        private static EquationOperation addMultiple(
            int target,
            int source,
            Rational multiplier
        ) {
            return new EquationOperation(
                OperationKind.ADD_EQUATION_MULTIPLE,
                target,
                source,
                multiplier);
        }

        private String canonical() {
            return switch (kind) {
                case SWAP_EQUATIONS -> "swap("
                    + targetEquation + "," + sourceEquation + ")";
                case SCALE_EQUATION -> "scale("
                    + targetEquation + "," + multiplier + ")";
                case ADD_EQUATION_MULTIPLE -> "add("
                    + targetEquation + "," + sourceEquation
                    + "," + multiplier + ")";
            };
        }
    }

    private static final class WorkCounter {
        private final int configured;
        private int sourceAnalysis;
        private int elimination;
        private int evidence;

        private WorkCounter(int configured) {
            this.configured = configured;
        }

        private void consume(Stage stage) {
            consume(stage, 1);
        }

        private void consume(Stage stage, int units) {
            if (units < 0) {
                throw new IllegalArgumentException(
                    "work units must not be negative");
            }
            int consumed = consumed();
            if (units > configured - consumed) {
                int remaining = configured - consumed;
                add(stage, remaining);
                throw new BudgetExceeded();
            }
            add(stage, units);
        }

        private void add(Stage stage, int units) {
            switch (stage) {
                case SOURCE_ANALYSIS -> sourceAnalysis += units;
                case ELIMINATION -> elimination += units;
                case EVIDENCE -> evidence += units;
            }
        }

        private int consumed() {
            return Math.addExact(
                sourceAnalysis,
                Math.addExact(elimination, evidence));
        }

        private StageWorkProfile profile() {
            return new StageWorkProfile(
                sourceAnalysis,
                elimination,
                evidence);
        }

        private WorkLedger ledger() {
            return WorkLedger.of(configured, consumed());
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
