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
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.representation.RepresentationBridge.Budget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Source-only representation selection followed by bounded exact solving.
 * Parsing and integer bit complexity are outside the mechanical work metric.
 * No matrix, rank computation or solution is consulted while preparing a plan.
 */
public final class LinearRepresentationPlanner {
    public enum Route { DIRECT, MATRIX, BLOCKS, AUTO }
    public static final int MAX_BUDGET = 1_000_000;

    /** Only this planner can construct a plan, so its source partition cannot be forged. */
    public static final class Plan {
        private final List<String> source;
        private final List<Equation> equations;
        private final List<String> variables;
        private final List<List<Integer>> blocks;
        private final int work;
        private final String status;
        private Plan(List<String> source, List<Equation> equations, List<String> variables,
                     List<List<Integer>> blocks, int work, String status) {
            this.source = List.copyOf(source);
            this.equations = List.copyOf(equations);
            this.variables = List.copyOf(variables);
            this.blocks = blocks.stream().map(List::copyOf).toList();
            this.work = work;
            this.status = status;
        }
        public List<String> source() { return source; }
        public List<String> variables() { return variables; }
        public List<List<Integer>> blocks() { return blocks; }
        public int work() { return work; }
        public String status() { return status; }
        public boolean independent() { return blocks.size() > 1; }
    }

    public record Step(List<Integer> sourceRows, String route, String status, int work,
                       List<String> operations) {
        public Step {
            sourceRows = List.copyOf(sourceRows);
            operations = List.copyOf(operations);
        }
    }
    public record Result(List<String> source, Route requested, Route selected, String status,
                         List<String> variables, List<List<Integer>> blocks, int budget,
                         int preparationWork, int executionWork, int compositionWork,
                         List<Step> steps, Optional<ExactLinearSolutionConsequence> solution) {
        public Result {
            source = List.copyOf(source);
            variables = List.copyOf(variables);
            blocks = blocks.stream().map(List::copyOf).toList();
            steps = List.copyOf(steps);
            Objects.requireNonNull(solution);
            if (budget < 0 || preparationWork < 0 || executionWork < 0 || compositionWork < 0
                    || (long) preparationWork + executionWork + compositionWork > budget
                    || status.equals("SOLVED") != solution.isPresent()) {
                throw new IllegalArgumentException("Invalid result/work ledger");
            }
        }
        public int totalWork() { return preparationWork + executionWork + compositionWork; }
    }
    public record Audit(String status, int work, Optional<Result> reference) {
        public Audit { Objects.requireNonNull(reference); }
        public boolean verified() { return status.equals("VERIFIED"); }
    }

    public Plan prepare(List<String> input, int budget) {
        checkBudget(budget);
        List<String> source = List.copyOf(input);
        if (source.isEmpty() || source.size() > 16) {
            throw new IllegalArgumentException("Expected 1 to 16 equations");
        }
        for (String text : source) validateText(text);
        var parser = new ExpressionParser();
        List<Equation> equations = source.stream().map(parser::parseEquation).toList();
        for (Equation equation : equations) {
            powerWeight(equation.left(), 0);
            powerWeight(equation.right(), 0);
        }
        Counter counter = new Counter(budget);
        Set<String> variables = new TreeSet<>();
        List<List<Integer>> blocks = new ArrayList<>();
        try {
            List<Set<String>> supports = new ArrayList<>();
            for (Equation equation : equations) {
                Set<String> support = new TreeSet<>();
                collect(equation.left(), support, counter, 0);
                collect(equation.right(), support, counter, 0);
                variables.addAll(support);
                supports.add(support);
            }
            if (variables.size() > 16) throw new IllegalArgumentException("At most 16 variables");
            if (variables.isEmpty()) return new Plan(source, equations, List.of(), List.of(),
                counter.used, "NOT_APPLICABLE");
            int[] parent = new int[source.size()];
            for (int i = 0; i < parent.length; i++) parent[i] = i;
            for (int i = 0; i < parent.length; i++) {
                for (int j = i + 1; j < parent.length; j++) {
                    boolean intersects = false;
                    for (String variable : supports.get(i)) {
                        counter.consume(1);
                        if (supports.get(j).contains(variable)) intersects = true;
                    }
                    if (intersects) parent[root(parent, j)] = root(parent, i);
                }
            }
            Map<Integer, List<Integer>> groups = new TreeMap<>();
            List<Integer> constants = new ArrayList<>();
            for (int i = 0; i < parent.length; i++) {
                counter.consume(1);
                if (supports.get(i).isEmpty()) constants.add(i);
                else groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(i);
            }
            // Constant rows, including contradictions, remain in the first nonempty component.
            groups.values().iterator().next().addAll(constants);
            for (List<Integer> rows : groups.values()) {
                Collections.sort(rows);
                blocks.add(List.copyOf(rows));
            }
            return new Plan(source, equations, List.copyOf(variables), blocks, counter.used, "PREPARED");
        } catch (Exhausted exception) {
            return new Plan(source, equations, List.copyOf(variables), List.of(), counter.used,
                "BUDGET_INCONCLUSIVE");
        }
    }

    public Result solve(List<String> source, Route route, int budget) {
        return execute(prepare(source, budget), route, budget);
    }

    public Result execute(Plan plan, Route route, int budget) {
        Objects.requireNonNull(plan);
        Objects.requireNonNull(route);
        checkBudget(budget);
        if (budget < plan.work) throw new IllegalArgumentException("Budget smaller than preparation work");
        Route selected = route == Route.AUTO
            ? plan.independent() && plan.variables.size() >= 8 ? Route.BLOCKS : Route.DIRECT : route;
        Counter work = new Counter(budget);
        work.consume(plan.work);
        List<Step> steps = new ArrayList<>();
        List<Local> solved = new ArrayList<>();
        String status = plan.status;
        int execution = 0;
        int composition = 0;
        Optional<ExactLinearSolutionConsequence> solution = Optional.empty();
        if (status.equals("PREPARED")) {
            List<List<Integer>> groups = selected == Route.BLOCKS ? plan.blocks
                : List.of(java.util.stream.IntStream.range(0, plan.source.size()).boxed().toList());
            for (List<Integer> rows : groups) {
                List<Equation> equations = rows.stream().map(plan.equations::get).toList();
                Local local = run(equations, plan.variables, selected == Route.DIRECT, work.remaining());
                work.consume(local.work);
                execution += local.work;
                steps.add(new Step(rows, selected == Route.DIRECT ? "DIRECT" : "MATRIX",
                    local.status, local.work, local.operations));
                status = local.status;
                if (!status.equals("SOLVED")) break;
                solved.add(local);
            }
            if (status.equals("SOLVED")) {
                int before = work.used;
                try {
                    solution = Optional.of(selected == Route.BLOCKS
                        ? compose(plan.variables, solved, work) : solved.getFirst().solution.orElseThrow());
                } catch (Exhausted exception) {
                    status = "BUDGET_INCONCLUSIVE";
                }
                composition = work.used - before;
            }
        }
        return new Result(plan.source, route, selected, status, plan.variables, plan.blocks, budget,
            plan.work, execution, composition, steps, solution);
    }

    /** Recompute a complete artifact, including failed work and the selection decision. */
    public boolean verify(Result result) {
        if (result == null) return false;
        try { return solve(result.source, result.requested, result.budget).equals(result); }
        catch (RuntimeException exception) { return false; }
    }

    /** Separate algorithmic audit: full matrix for scalar/block construction, scalar for matrix. */
    public Audit audit(Result result, int budget) {
        if (!result.status.equals("SOLVED")) return new Audit("NOT_SOLVED", 0, Optional.empty());
        Result reference = solve(result.source, result.selected == Route.MATRIX ? Route.DIRECT : Route.MATRIX, budget);
        String status = reference.solution.isEmpty() ? "INCONCLUSIVE"
            : reference.solution.equals(result.solution) ? "VERIFIED" : "MISMATCH";
        return new Audit(status, reference.totalWork(), Optional.of(reference));
    }

    private record Local(String status, int work, Optional<ExactLinearSolutionConsequence> solution,
                         List<String> freeVariables, List<String> operations) { }
    private static Local run(List<Equation> equations, List<String> variables, boolean direct, int budget) {
        if (direct) {
            var result = new DirectScalarEliminationSolver().solve(
                new DirectScalarEliminationSolver.Source(equations, variables), new Budget(budget));
            return new Local(result.status().name(), result.work().consumedWorkUnits(), result.consequence(),
                List.of(), result.certificate().map(c -> c.canonicalOperations()).orElse(List.of()));
        }
        var matrix = new LinearSystemRepresentationBridge().analyze(equations, new Budget(budget));
        int spent = matrix.work().consumedWorkUnits();
        if (!matrix.represented()) return new Local(matrix.status().name(), spent, Optional.empty(), List.of(), List.of());
        var result = new ExactRrefSolver().solve(matrix.representation().orElseThrow(), new Budget(budget - spent));
        spent += result.work().consumedWorkUnits();
        if (result.reduction().isEmpty()) return new Local(result.status().name(), spent, Optional.empty(), List.of(), List.of());
        var reduction = result.reduction().orElseThrow();
        return new Local("SOLVED", spent, Optional.of(ExactLinearSolutionConsequence.fromRref(reduction)),
            reduction.freeVariableColumns().stream().map(reduction.variables()::get).toList(),
            reduction.rowOperations().stream().map(Object::toString).toList());
    }

    private static ExactLinearSolutionConsequence compose(List<String> variables, List<Local> parts, Counter work) {
        List<Rational> particular = new ArrayList<>(Collections.nCopies(variables.size(), Rational.ZERO));
        Map<Integer, ExactVector> basis = new TreeMap<>();
        boolean inconsistent = false;
        for (Local part : parts) {
            var consequence = part.solution.orElseThrow();
            work.consume(1);
            if (consequence.classification() == SolutionClassification.INCONSISTENT) {
                inconsistent = true;
                continue;
            }
            for (int i = 0; i < consequence.variables().size(); i++) {
                work.consume(1);
                particular.set(variables.indexOf(consequence.variables().get(i)), consequence.particularSolution().orElseThrow().get(i));
            }
            for (int b = 0; b < consequence.nullspaceBasis().size(); b++) {
                List<Rational> vector = new ArrayList<>(Collections.nCopies(variables.size(), Rational.ZERO));
                work.consume(variables.size());
                for (int i = 0; i < consequence.variables().size(); i++) {
                    work.consume(1);
                    vector.set(variables.indexOf(consequence.variables().get(i)), consequence.nullspaceBasis().get(b).get(i));
                }
                basis.put(variables.indexOf(part.freeVariables.get(b)), new ExactVector(vector));
            }
        }
        if (inconsistent) return new ExactLinearSolutionConsequence(variables, SolutionClassification.INCONSISTENT,
            Optional.empty(), List.of(), Optional.of(Rational.ONE));
        return new ExactLinearSolutionConsequence(variables, basis.isEmpty() ? SolutionClassification.UNIQUE
            : SolutionClassification.UNDERDETERMINED, Optional.of(new ExactVector(particular)),
            List.copyOf(basis.values()), Optional.empty());
    }

    private static void collect(Expr expression, Set<String> names, Counter work, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Expression depth exceeds 64");
        work.consume(1);
        if (expression instanceof VariableExpr variable) names.add(variable.name());
        else if (expression instanceof BinaryExpr binary) {
            collect(binary.left(), names, work, depth + 1);
            collect(binary.right(), names, work, depth + 1);
        } else if (expression instanceof FunctionExpr function) {
            for (Expr argument : function.arguments()) collect(argument, names, work, depth + 1);
        } else if (!(expression instanceof NumberExpr)) throw new IllegalArgumentException("Unknown expression node");
    }
    private static int root(int[] parent, int i) {
        while (parent[i] != i) i = parent[i];
        return i;
    }
    private static void validateText(String text) {
        if (text == null || text.isBlank() || text.length() > 512)
            throw new IllegalArgumentException("Each equation must contain 1 to 512 characters");
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '(' && ++depth > 64) throw new IllegalArgumentException("Expression nesting exceeds 64");
            if (c == ')') depth--;
        }
        var numbers = java.util.regex.Pattern.compile(
            "(?i)(?<![a-z_0-9])(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:e([+-]?[0-9]+))?").matcher(text);
        while (numbers.find()) {
            if (numbers.group().length() > 128) throw new IllegalArgumentException("Numeric literal exceeds 128 characters");
            if (numbers.group(1) != null) {
                java.math.BigInteger exponent = new java.math.BigInteger(numbers.group(1));
                if (exponent.abs().compareTo(java.math.BigInteger.valueOf(64)) > 0)
                    throw new IllegalArgumentException("Decimal exponent exceeds 64");
            }
        }
    }
    // Syntactic input bound: nested constant powers must not evade a mechanical operation budget.
    private static int powerWeight(Expr expression, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Expression depth exceeds 64");
        if (expression instanceof BinaryExpr binary) {
            int left = powerWeight(binary.left(), depth + 1);
            int right = powerWeight(binary.right(), depth + 1);
            if (binary.operator() != BinaryOperator.POW) return Math.max(left, right);
            int factor = 64;
            if (binary.right() instanceof NumberExpr number && number.value().isInteger()) {
                var absolute = number.value().numerator().abs();
                factor = absolute.compareTo(java.math.BigInteger.valueOf(64)) > 0 ? 64 : Math.max(1, absolute.intValue());
            }
            if (left * factor > 64) throw new IllegalArgumentException("Nested power weight exceeds 64");
            return Math.max(left * factor, right);
        }
        if (expression instanceof FunctionExpr function) {
            int maximum = 1;
            for (Expr argument : function.arguments()) maximum = Math.max(maximum, powerWeight(argument, depth + 1));
            return maximum;
        }
        return 1;
    }
    private static void checkBudget(int budget) {
        if (budget < 0 || budget > MAX_BUDGET) throw new IllegalArgumentException("Budget must be between 0 and 1000000");
    }
    private static final class Counter {
        private final int limit;
        private int used;
        private Counter(int limit) { this.limit = limit; }
        private int remaining() { return limit - used; }
        private void consume(int amount) {
            if (amount > remaining()) { used = limit; throw new Exhausted(); }
            used += amount;
        }
    }
    private static final class Exhausted extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
