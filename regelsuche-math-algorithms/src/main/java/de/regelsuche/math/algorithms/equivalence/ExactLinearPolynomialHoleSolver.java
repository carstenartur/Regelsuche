package de.regelsuche.math.algorithms.equivalence;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.math.algorithms.linalg.ExactLinearSystem;
import de.regelsuche.math.algorithms.linalg.ExactRrefSolver;
import de.regelsuche.representation.RepresentationBridge.Budget;
import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rational coefficient matching in a frozen polynomial template, without enumerating candidate values.
 * Only an affine dependence on declared holes and an empty assumption context are supported.
 * A result is mathematical discovery data, never an executable plan or a promotion authority.
 */
public final class ExactLinearPolynomialHoleSolver {
    public static final String SOLVER_ID = "regelsuche.exact-linear-polynomial-hole-solver/v1";
    public static final String REVISION = "exact-affine-hole-projection/bounded-rref/independent-identity/v1";
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{2,63}");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-z][a-z0-9_-]{2,63})}");

    public Result solve(String sourceExpression, String ansatzTemplate, List<String> holeIds,
        List<String> assumptions, Limits limits) {
        String source = requireText(sourceExpression, "sourceExpression");
        String template = requireText(ansatzTemplate, "ansatzTemplate");
        List<String> holes = normalizedHoles(holeIds);
        List<String> retainedAssumptions = List.copyOf(Objects.requireNonNull(assumptions, "assumptions"));
        Objects.requireNonNull(limits, "limits");
        Work work = new Work(limits.maxWorkUnits());
        List<Constraint> constraints = List.of();
        Optional<ExactRrefSolver.Result> reduction = Optional.empty();
        try {
            if (!retainedAssumptions.isEmpty()) { throw new Unsupported("NONEMPTY_ASSUMPTION_CONTEXT"); }
            if (holes.size() > limits.maxHoles()) { throw new LimitExceeded("HOLE_DIMENSION_LIMIT"); }
            Map<String, String> symbols = freshSymbols(source, template, holes, work);
            String symbolic = instantiate(template, symbols);
            var arithmetic = new ExactResidualPolynomialArithmetic(null, count -> work.spend(Stage.PROJECTION, count));
            // Reject syntactic nonlinear hole dependence even when later polynomial cancellation would hide it.
            work.spend(Stage.PROJECTION, symbolic.length());
            holeDegree(arithmetic.exactTerm(symbolic).expression(), new HashSet<>(symbols.values()), work);
            Polynomial residual = arithmetic.parse("(" + symbolic + ")-(" + source + ")");
            constraints = constraints(residual, holes, symbols, limits, work);
            var system = system(holes, constraints);
            var solved = new ExactRrefSolver().solveCoefficients(system, new Budget(work.remaining()), limits.maxScalarBits());
            work.spend(Stage.ELIMINATION, solved.work().consumedWorkUnits());
            reduction = Optional.of(solved);
            if (solved.status() == ExactRrefSolver.Status.BUDGET_INCONCLUSIVE) {
                return result(source, template, holes, retainedAssumptions, limits, Status.BUDGET_INCONCLUSIVE,
                    constraints, reduction, Optional.empty(), work, solved.detailCode());
            }
            if (solved.status() != ExactRrefSolver.Status.SOLVED) {
                return result(source, template, holes, retainedAssumptions, limits, Status.CHECK_FAILED,
                    constraints, reduction, Optional.empty(), work, solved.detailCode());
            }
            var rref = solved.reduction().orElseThrow();
            if (!rref.contradictionRows().isEmpty()) {
                return result(source, template, holes, retainedAssumptions, limits, Status.INCONSISTENT,
                    constraints, reduction, Optional.empty(), work, "CONTRADICTORY_COEFFICIENT_EQUATIONS");
            }
            if (!rref.freeVariableColumns().isEmpty()) {
                return result(source, template, holes, retainedAssumptions, limits, Status.UNDERDETERMINED,
                    constraints, reduction, Optional.empty(), work, "NO_UNIQUE_COEFFICIENT_VECTOR");
            }
            Map<String, ExactRational> bindings = new TreeMap<>();
            Map<String, String> replacements = new TreeMap<>();
            for (int index = 0; index < holes.size(); index++) {
                work.spend(Stage.VERIFICATION, 1);
                ExactRational value = rref.particularSolution().orElseThrow().get(index).exactValue();
                bindings.put(holes.get(index), value);
                replacements.put(holes.get(index), value.canonicalText());
            }
            String instantiated = instantiate(template, replacements);
            // This checks the original source and complete instantiated template, not the derived matrix.
            new ExactPolynomialAnalysis(null, count -> work.spend(Stage.VERIFICATION, count))
                .requireEquivalent(source, instantiated);
            return result(source, template, holes, retainedAssumptions, limits, Status.UNIQUE, constraints,
                reduction, Optional.of(new Candidate(bindings, instantiated)), work, "UNIQUE_COEFFICIENTS_INDEPENDENTLY_CHECKED");
        } catch (WorkExceeded exception) {
            return result(source, template, holes, retainedAssumptions, limits, Status.BUDGET_INCONCLUSIVE,
                constraints, reduction, Optional.empty(), work, "CUMULATIVE_WORK_BUDGET_EXHAUSTED");
        } catch (LimitExceeded exception) {
            return result(source, template, holes, retainedAssumptions, limits, Status.BUDGET_INCONCLUSIVE,
                constraints, reduction, Optional.empty(), work, exception.getMessage());
        } catch (ExactResidualPolynomialArithmetic.ProjectionLimitExceeded exception) {
            return result(source, template, holes, retainedAssumptions, limits, Status.BUDGET_INCONCLUSIVE,
                constraints, reduction, Optional.empty(), work, "PROJECTION_LIMIT:" + exception.getMessage());
        } catch (Unsupported exception) {
            return result(source, template, holes, retainedAssumptions, limits, Status.UNSUPPORTED,
                constraints, reduction, Optional.empty(), work, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            return result(source, template, holes, retainedAssumptions, limits, Status.UNSUPPORTED,
                constraints, reduction, Optional.empty(), work, "EXACT_POLYNOMIAL_FRAGMENT:" + exception.getMessage());
        }
    }

    /** Recomputes formation, all row operations and independent source/template checking. */
    public boolean replay(Result expected) {
        Objects.requireNonNull(expected, "expected");
        try {
            return solve(expected.sourceExpression(), expected.ansatzTemplate(), expected.holeIds(),
                expected.assumptions(), expected.limits()).equals(expected);
        } catch (IllegalArgumentException exception) { return false; }
    }

    private static Map<String, String> freshSymbols(String source, String template, List<String> holes, Work work) {
        Map<String, String> symbols = new LinkedHashMap<>();
        int index = 0;
        for (String hole : holes) {
            String symbol;
            do {
                work.spend(Stage.PROJECTION, source.length() + (long) template.length());
                symbol = "linearcoefficient" + index++;
            } while (source.contains(symbol) || template.contains(symbol));
            symbols.put(hole, symbol);
        }
        return symbols;
    }

    private static String instantiate(String template, Map<String, String> bindings) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        HashSet<String> used = new HashSet<>();
        while (matcher.find()) {
            String id = matcher.group(1);
            String value = bindings.get(id);
            if (value == null) { throw new Unsupported("UNDECLARED_COEFFICIENT_HOLE"); }
            used.add(id);
            matcher.appendReplacement(result, Matcher.quoteReplacement("(" + value + ")"));
        }
        matcher.appendTail(result);
        if (!used.equals(bindings.keySet()) || result.indexOf("$") >= 0
                || result.indexOf("{") >= 0 || result.indexOf("}") >= 0) {
            throw new Unsupported("TEMPLATE_AND_DECLARED_HOLES_DIFFER");
        }
        return result.toString();
    }

    private static int holeDegree(Expr expression, java.util.Set<String> symbols, Work work) {
        work.spend(Stage.PROJECTION, 1);
        if (expression instanceof NumberExpr) { return 0; }
        if (expression instanceof VariableExpr variable) { return symbols.contains(variable.name()) ? 1 : 0; }
        if (!(expression instanceof BinaryExpr binary)) { throw new Unsupported("NONPOLYNOMIAL_TEMPLATE"); }
        int left = holeDegree(binary.left(), symbols, work);
        int right = holeDegree(binary.right(), symbols, work);
        int degree = switch (binary.operator()) {
            case ADD, SUB -> Math.max(left, right);
            case MUL -> left + right;
            case DIV -> {
                if (right != 0) { throw new Unsupported("HOLE_DEPENDENT_DENOMINATOR"); }
                yield left;
            }
            case POW -> {
                if (!(binary.right() instanceof NumberExpr number) || !number.value().isInteger()
                        || number.value().numerator().signum() < 0 || number.value().numerator().bitLength() > 6) {
                    throw new Unsupported("NONCONSTANT_TEMPLATE_EXPONENT");
                }
                yield left * number.value().numerator().intValueExact();
            }
        };
        if (degree > 1) { throw new Unsupported("NONLINEAR_COEFFICIENT_HOLES"); }
        return degree;
    }

    private static List<Constraint> constraints(Polynomial residual, List<String> holes, Map<String, String> symbols,
        Limits limits, Work work) {
        Map<String, Rational[]> rows = new TreeMap<>();
        var terms = residual.terms().entrySet().stream().sorted(Map.Entry.comparingByKey(
            java.util.Comparator.comparing(Monomial::key))).toList();
        for (var term : terms) {
            work.spend(Stage.CONSTRAINTS, holes.size() + 1L);
            Monomial monomial = term.getKey();
            int column = holes.size();
            for (int index = 0; index < holes.size(); index++) {
                String symbol = symbols.get(holes.get(index));
                int exponent = monomial.exponentOf(symbol);
                if (exponent > 0) {
                    if (exponent != 1 || column != holes.size()) { throw new Unsupported("NONLINEAR_COEFFICIENT_HOLES"); }
                    column = index;
                    monomial = monomial.without(symbol);
                }
            }
            Rational[] row = rows.get(monomial.key());
            if (row == null) {
                if (rows.size() >= limits.maxMonomials()) { throw new LimitExceeded("MONOMIAL_DIMENSION_LIMIT"); }
                work.spend(Stage.CONSTRAINTS, holes.size() + 1L);
                row = new Rational[holes.size() + 1];
                java.util.Arrays.fill(row, Rational.ZERO);
                rows.put(monomial.key(), row);
            }
            // Normal form has at most one term per (base monomial, hole) pair, so no unmetered rational sum is needed.
            Rational value = column == holes.size() ? term.getValue().negate() : term.getValue();
            if (value.numerator().abs().bitLength() > limits.maxScalarBits()
                    || value.denominator().bitLength() > limits.maxScalarBits()) {
                throw new LimitExceeded("COEFFICIENT_BIT_LIMIT");
            }
            if (!row[column].isZero()) { throw new IllegalStateException("duplicate normalized coefficient monomial"); }
            row[column] = value;
        }
        if (rows.isEmpty()) {
            work.spend(Stage.CONSTRAINTS, holes.size() + 1L);
            Rational[] zero = new Rational[holes.size() + 1];
            java.util.Arrays.fill(zero, Rational.ZERO);
            rows.put("", zero);
        }
        List<Constraint> result = new ArrayList<>();
        for (var row : rows.entrySet()) {
            work.spend(Stage.CONSTRAINTS, holes.size() + 1L);
            result.add(new Constraint(row.getKey(), List.of(row.getValue()).subList(0, holes.size()), row.getValue()[holes.size()]));
        }
        return List.copyOf(result);
    }

    private static ExactRrefSolver.CoefficientSystem system(List<String> holes, List<Constraint> constraints) {
        List<ExactLinearSystem.RowOrigin> origins = new ArrayList<>();
        for (int index = 0; index < constraints.size(); index++) {
            origins.add(new ExactLinearSystem.RowOrigin(index, "template-minus-source coefficient of monomial ["
                + constraints.get(index).monomial() + "]"));
        }
        return new ExactRrefSolver.CoefficientSystem(new ExactLinearSystem.ExactMatrix(
            constraints.stream().map(Constraint::coefficients).toList()), holes,
            new ExactLinearSystem.ExactVector(constraints.stream().map(Constraint::rightHandSide).toList()), origins);
    }

    private static Result result(String source, String template, List<String> holes, List<String> assumptions, Limits limits,
        Status status, List<Constraint> constraints, Optional<ExactRrefSolver.Result> reduction, Optional<Candidate> candidate,
        Work work, String detail) {
        return new Result(source, template, holes, assumptions, limits, status, constraints, reduction, candidate, work.snapshot(), detail);
    }

    public enum Status { UNIQUE, INCONSISTENT, UNDERDETERMINED, UNSUPPORTED, BUDGET_INCONCLUSIVE, CHECK_FAILED }

    public record Limits(int maxHoles, int maxMonomials, int maxScalarBits, int maxWorkUnits) {
        public Limits {
            if (maxHoles < 1 || maxHoles > 12 || maxMonomials < 1 || maxMonomials > 128
                    || maxScalarBits < 1 || maxScalarBits > 4_096 || maxWorkUnits < 0 || maxWorkUnits > 10_000_000) {
                throw new IllegalArgumentException("linear coefficient limits exceed the bounded fragment");
            }
        }
    }

    public record Constraint(String monomial, List<Rational> coefficients, Rational rightHandSide) {
        public Constraint {
            Objects.requireNonNull(monomial, "monomial");
            coefficients = List.copyOf(coefficients);
            Objects.requireNonNull(rightHandSide, "rightHandSide");
        }
    }

    public record Candidate(Map<String, ExactRational> bindings, String instantiatedExpression) {
        public Candidate {
            bindings = Collections.unmodifiableMap(new TreeMap<>(Map.copyOf(bindings)));
            instantiatedExpression = requireText(instantiatedExpression, "instantiatedExpression");
        }
    }

    public record WorkProfile(int configured, int projection, int constraints, int elimination, int verification) {
        public WorkProfile {
            if (configured < 0 || projection < 0 || constraints < 0 || elimination < 0 || verification < 0
                    || (long) projection + constraints + elimination + verification > configured) {
                throw new IllegalArgumentException("linear coefficient work must be nonnegative and bounded");
            }
        }
        public int consumed() { return Math.addExact(Math.addExact(projection, constraints), Math.addExact(elimination, verification)); }
        public int remaining() { return configured - consumed(); }
    }

    public record Result(String sourceExpression, String ansatzTemplate, List<String> holeIds, List<String> assumptions,
        Limits limits, Status status, List<Constraint> constraints, Optional<ExactRrefSolver.Result> reduction,
        Optional<Candidate> candidate, WorkProfile work, String detailCode) {
        public Result {
            sourceExpression = requireText(sourceExpression, "sourceExpression");
            ansatzTemplate = requireText(ansatzTemplate, "ansatzTemplate");
            holeIds = normalizedHoles(holeIds);
            assumptions = List.copyOf(assumptions);
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(status, "status");
            constraints = List.copyOf(constraints);
            Objects.requireNonNull(reduction, "reduction");
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(work, "work");
            detailCode = requireText(detailCode, "detailCode");
            if ((status == Status.UNIQUE) != candidate.isPresent() || work.configured() != limits.maxWorkUnits()
                    || candidate.isPresent() && (!candidate.orElseThrow().bindings().keySet().equals(new HashSet<>(holeIds))
                        || reduction.isEmpty() || reduction.orElseThrow().status() != ExactRrefSolver.Status.SOLVED)) {
                throw new IllegalArgumentException("linear coefficient candidate and terminal evidence disagree");
            }
        }

        /** Additive identity; old finite-solver and RREF receipt identities are never relabelled. */
        public String contentHash() {
            StringBuilder value = new StringBuilder();
            append(value, SOLVER_ID, REVISION, ExactRrefSolver.SOLVER_ID, ExactPolynomialAnalysis.REVISION,
                sourceExpression, ansatzTemplate, Integer.toString(holeIds.size()));
            holeIds.forEach(id -> append(value, "COEFFICIENT", id));
            append(value, Integer.toString(assumptions.size()));
            assumptions.forEach(item -> append(value, item));
            append(value, Integer.toString(limits.maxHoles()), Integer.toString(limits.maxMonomials()),
                Integer.toString(limits.maxScalarBits()), Integer.toString(limits.maxWorkUnits()), status.name(), detailCode,
                Integer.toString(work.projection()), Integer.toString(work.constraints()), Integer.toString(work.elimination()),
                Integer.toString(work.verification()), Integer.toString(constraints.size()));
            for (Constraint row : constraints) {
                append(value, row.monomial(), Integer.toString(row.coefficients().size()));
                row.coefficients().forEach(coefficient -> append(value, coefficient.toString()));
                append(value, row.rightHandSide().toString());
            }
            append(value, Boolean.toString(reduction.isPresent()));
            reduction.ifPresent(item -> append(value, item.status().name(), item.detailCode(),
                Integer.toString(item.work().configuredWorkUnits()), Integer.toString(item.work().consumedWorkUnits()),
                item.certificate().map(ExactRrefSolver.Certificate::contentHash).orElse("")));
            reduction.flatMap(ExactRrefSolver.Result::certificate).ifPresent(item -> {
                append(value, item.schema(), item.solverId(), item.relation().name(), item.sourceSystemHash(),
                    item.solutionClassification().name(), Integer.toString(item.reducedAugmentedRows().size()));
                item.reducedAugmentedRows().forEach(row -> appendList(value, row));
                appendList(value, item.canonicalOperations());
                append(value, Integer.toString(item.coefficientPivots().size()));
                item.coefficientPivots().forEach(pivot -> append(value, Integer.toString(pivot.row()), Integer.toString(pivot.column())));
                appendList(value, item.freeVariableColumns().stream().map(Object::toString).toList());
                appendList(value, item.contradictionRows().stream().map(Object::toString).toList());
                appendList(value, item.particularSolution());
                append(value, Integer.toString(item.nullspaceBasis().size()));
                item.nullspaceBasis().forEach(vector -> appendList(value, vector));
                appendList(value, item.capabilitiesBefore());
                appendList(value, item.capabilitiesAfter());
                appendList(value, item.newlyUnlockedCapabilities());
                appendList(value, item.lostOrConditionalCapabilities());
            });
            reduction.flatMap(ExactRrefSolver.Result::reduction).ifPresent(item -> {
                // Bind concrete supplied lineage as well as its certificate. A public Result is data,
                // and its nested reduction must not hide behind another object's valid certificate hash.
                appendList(value, item.variables());
                append(value, item.relation().name(), Integer.toString(item.reducedAugmentedRows().size()));
                item.reducedAugmentedRows().forEach(row -> appendList(value, row.stream().map(Rational::toString).toList()));
                append(value, Integer.toString(item.coefficientPivots().size()));
                item.coefficientPivots().forEach(pivot -> append(value, Integer.toString(pivot.row()), Integer.toString(pivot.column())));
                appendList(value, item.freeVariableColumns().stream().map(Object::toString).toList());
                appendList(value, item.contradictionRows().stream().map(Object::toString).toList());
                append(value, Boolean.toString(item.particularSolution().isPresent()));
                item.particularSolution().ifPresent(vector -> appendList(value, vector.values().stream().map(Rational::toString).toList()));
                append(value, Integer.toString(item.nullspaceBasis().size()));
                item.nullspaceBasis().forEach(vector -> appendList(value, vector.values().stream().map(Rational::toString).toList()));
                appendList(value, item.rowOperations().stream().map(de.regelsuche.math.algorithms.linalg.ExactRrefReduction.RowOperation::canonicalForm).toList());
                appendList(value, item.capabilityFrontier().applicableBefore());
                appendList(value, item.capabilityFrontier().applicableAfter());
                appendList(value, item.capabilityFrontier().newlyUnlocked());
                appendList(value, item.capabilityFrontier().lostOrConditional());
            });
            append(value, Boolean.toString(candidate.isPresent()));
            candidate.ifPresent(item -> {
                item.bindings().forEach((id, scalar) -> append(value, id, scalar.canonicalText()));
                append(value, item.instantiatedExpression());
            });
            try {
                return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        }
    }

    private static void append(StringBuilder target, String... values) {
        for (String value : values) { target.append(value.length()).append(':').append(value); }
    }

    private static void appendList(StringBuilder target, List<String> values) {
        append(target, Integer.toString(values.size()));
        values.forEach(value -> append(target, value));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) { throw new IllegalArgumentException(name + " must not be blank"); }
        if (value.length() > 16_384) { throw new IllegalArgumentException(name + " exceeds text limit"); }
        return value.trim();
    }

    private static List<String> normalizedHoles(List<String> values) {
        Objects.requireNonNull(values, "holeIds");
        if (values.isEmpty() || values.size() > 12 || values.stream().anyMatch(id -> id == null || !ID.matcher(id).matches())
                || new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("coefficient holes must be nonempty, bounded and unique valid IDs");
        }
        return values.stream().sorted().toList();
    }

    private enum Stage { PROJECTION, CONSTRAINTS, ELIMINATION, VERIFICATION }

    private static final class Work {
        private final int configured;
        private final int[] stages = new int[Stage.values().length];
        private int consumed;
        private Work(int configured) { this.configured = configured; }
        private int remaining() { return configured - consumed; }
        private void spend(Stage stage, long units) {
            if (units < 0) { throw new IllegalArgumentException("negative work"); }
            int accepted = (int) Math.min(units, remaining());
            stages[stage.ordinal()] += accepted;
            consumed += accepted;
            if (accepted != units) { throw new WorkExceeded(); }
        }
        private WorkProfile snapshot() { return new WorkProfile(configured, stages[0], stages[1], stages[2], stages[3]); }
    }

    private static final class WorkExceeded extends RuntimeException { private static final long serialVersionUID = 1L; }
    private static final class LimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private LimitExceeded(String detail) { super(detail); }
    }
    private static final class Unsupported extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private Unsupported(String detail) { super(detail); }
    }
}
