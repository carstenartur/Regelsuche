package de.regelsuche.benchmark;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.AssociativeCommutativeValue;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.NumberValue;
import de.regelsuche.value.ExprValueFactory.OrderedValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import de.regelsuche.value.ExprValueFactory.VariableValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded experiment comparing syntax-tree evaluation with a cached value-DAG plan.
 *
 * <p>The owner keeps one scoped {@link ExprValueFactory} and a bounded LRU cache of
 * evaluation plans keyed by {@link ValueKey}. A plan contains one instruction per
 * distinct mathematical value, so repeated pure subexpressions are evaluated once
 * per invocation. No occurrence-specific trace data is stored on shared values.</p>
 */
public final class ValueDagEvaluationExperiment implements AutoCloseable {
    private final ExprValueFactory valueFactory;
    private final BoundedValueCache<EvaluationPlan> planCache;
    private long planCacheHits;
    private long planCacheMisses;
    private boolean closed;

    public ValueDagEvaluationExperiment(int maximumValues, int maximumPlans) {
        valueFactory = new ExprValueFactory(maximumValues);
        planCache = new BoundedValueCache<>(maximumPlans);
    }

    /** Runs both evaluators and returns operation counts plus timing observations. */
    public synchronized Comparison compare(Expr syntax, Map<String, Double> variables) {
        ensureOpen();
        Objects.requireNonNull(syntax, "syntax");
        Map<String, Double> bindings = Map.copyOf(Objects.requireNonNull(variables, "variables"));

        long treeStarted = System.nanoTime();
        TreeExecution tree = evaluateTree(syntax, bindings);
        long treeNanos = System.nanoTime() - treeStarted;

        long projectionStarted = System.nanoTime();
        ExprValue root = valueFactory.project(syntax).valueRoot();
        long projectionNanos = System.nanoTime() - projectionStarted;

        EvaluationRun dag = evaluateValue(root, bindings);
        return new Comparison(
                tree.value(),
                dag.value(),
                tree.evaluations(),
                dag.evaluations(),
                dag.distinctValues(),
                projectionNanos,
                dag.planConstructionNanos(),
                treeNanos,
                dag.executionNanos(),
                dag.planCacheHit(),
                planCacheHits,
                planCacheMisses,
                planCache.evictions(),
                planCache.size(),
                valueFactory.size());
    }

    public synchronized int cachedPlanCount() {
        ensureOpen();
        return planCache.size();
    }

    public synchronized int internedValueCount() {
        ensureOpen();
        return valueFactory.size();
    }

    @Override
    public synchronized void close() {
        planCache.clear();
        valueFactory.close();
        closed = true;
    }

    private EvaluationRun evaluateValue(ExprValue root, Map<String, Double> variables) {
        EvaluationPlan plan = planCache.get(root.key());
        boolean cacheHit = plan != null;
        long constructionNanos = 0L;
        if (cacheHit) {
            planCacheHits++;
        } else {
            planCacheMisses++;
            long constructionStarted = System.nanoTime();
            plan = compile(root);
            constructionNanos = System.nanoTime() - constructionStarted;
            planCache.put(root.key(), plan);
        }

        long executionStarted = System.nanoTime();
        Execution execution = execute(plan, variables);
        long executionNanos = System.nanoTime() - executionStarted;
        return new EvaluationRun(
                execution.value(),
                execution.evaluations(),
                plan.instructions().size(),
                constructionNanos,
                executionNanos,
                cacheHit);
    }

    private static EvaluationPlan compile(ExprValue root) {
        Map<ValueKey, Integer> slotsByValue = new LinkedHashMap<>();
        List<Instruction> instructions = new ArrayList<>();
        int rootSlot = compile(root, slotsByValue, instructions);
        return new EvaluationPlan(List.copyOf(instructions), rootSlot);
    }

    private static int compile(
            ExprValue value,
            Map<ValueKey, Integer> slotsByValue,
            List<Instruction> instructions) {
        Integer existing = slotsByValue.get(value.key());
        if (existing != null) {
            return existing;
        }

        Instruction instruction;
        if (value instanceof NumberValue number) {
            instruction = Instruction.number(number.value());
        } else if (value instanceof VariableValue variable) {
            instruction = Instruction.variable(variable.name());
        } else if (value instanceof OrderedValue ordered) {
            List<Integer> operands = ordered.operands().stream()
                    .map(operand -> compile(operand, slotsByValue, instructions))
                    .toList();
            instruction = Instruction.operation(ordered.operator().id(), operands, List.of());
        } else if (value instanceof AssociativeCommutativeValue ac) {
            List<Map.Entry<ExprValue, Integer>> entries = new ArrayList<>(ac.multiplicities().entrySet());
            entries.sort(Comparator.comparing(entry -> entry.getKey().key()));
            List<Integer> operands = new ArrayList<>(entries.size());
            List<Integer> multiplicities = new ArrayList<>(entries.size());
            for (Map.Entry<ExprValue, Integer> entry : entries) {
                operands.add(compile(entry.getKey(), slotsByValue, instructions));
                multiplicities.add(entry.getValue());
            }
            instruction = Instruction.operation(ac.operator().id(), operands, multiplicities);
        } else {
            throw new IllegalArgumentException("unsupported value type: " + value.getClass());
        }

        int slot = instructions.size();
        instructions.add(instruction);
        slotsByValue.put(value.key(), slot);
        return slot;
    }

    private static Execution execute(EvaluationPlan plan, Map<String, Double> variables) {
        double[] values = new double[plan.instructions().size()];
        int evaluations = 0;
        for (int slot = 0; slot < plan.instructions().size(); slot++) {
            Instruction instruction = plan.instructions().get(slot);
            values[slot] = switch (instruction.kind()) {
                case NUMBER -> instruction.number();
                case VARIABLE -> requireVariable(variables, instruction.variable());
                case OPERATION -> evaluateOperation(instruction, values);
            };
            evaluations++;
        }
        return new Execution(values[plan.rootSlot()], evaluations);
    }

    private static double evaluateOperation(Instruction instruction, double[] values) {
        String operator = instruction.operator();
        List<Integer> operands = instruction.operands();
        return switch (operator) {
            case "add" -> evaluateSum(instruction, values);
            case "mul" -> evaluateProduct(instruction, values);
            case "sub" -> values[operands.get(0)] - values[operands.get(1)];
            case "div" -> divide(values[operands.get(0)], values[operands.get(1)]);
            case "pow" -> Math.pow(values[operands.get(0)], values[operands.get(1)]);
            default -> evaluateFunction(operator, operands, values);
        };
    }

    private static double evaluateSum(Instruction instruction, double[] values) {
        double result = 0.0;
        for (int i = 0; i < instruction.operands().size(); i++) {
            result += values[instruction.operands().get(i)] * instruction.multiplicity(i);
        }
        return result;
    }

    private static double evaluateProduct(Instruction instruction, double[] values) {
        double result = 1.0;
        for (int i = 0; i < instruction.operands().size(); i++) {
            result *= Math.pow(values[instruction.operands().get(i)], instruction.multiplicity(i));
        }
        return result;
    }

    private static double evaluateFunction(String operator, List<Integer> operands, double[] values) {
        if (!operator.startsWith("fn:") || operands.size() != 1) {
            throw new IllegalArgumentException("unsupported ordered operator: " + operator);
        }
        double argument = values[operands.get(0)];
        return switch (operator.substring(3)) {
            case "sin" -> Math.sin(argument);
            case "cos" -> Math.cos(argument);
            case "tan" -> Math.tan(argument);
            case "log" -> argument <= 0 ? Double.NaN : Math.log10(argument);
            case "ln" -> argument <= 0 ? Double.NaN : Math.log(argument);
            case "sqrt" -> argument < 0 ? Double.NaN : Math.sqrt(argument);
            case "exp" -> Math.exp(argument);
            case "abs" -> Math.abs(argument);
            default -> throw new IllegalArgumentException("unsupported function: " + operator);
        };
    }

    private static TreeExecution evaluateTree(Expr expression, Map<String, Double> variables) {
        if (expression instanceof NumberExpr number) {
            return new TreeExecution(number.value(), 1);
        }
        if (expression instanceof VariableExpr variable) {
            return new TreeExecution(requireVariable(variables, variable.name()), 1);
        }
        if (expression instanceof FunctionExpr function) {
            if (function.arguments().size() != 1) {
                throw new IllegalArgumentException("only unary functions are supported");
            }
            TreeExecution argument = evaluateTree(function.arguments().getFirst(), variables);
            Instruction call = Instruction.operation(
                    "fn:" + function.name(), List.of(0), List.of());
            return new TreeExecution(
                    evaluateFunction(call.operator(), call.operands(), new double[] {argument.value()}),
                    argument.evaluations() + 1);
        }
        if (expression instanceof BinaryExpr binary) {
            TreeExecution left = evaluateTree(binary.left(), variables);
            TreeExecution right = evaluateTree(binary.right(), variables);
            return new TreeExecution(
                    evaluateBinary(binary.operator(), left.value(), right.value()),
                    left.evaluations() + right.evaluations() + 1);
        }
        throw new IllegalArgumentException("unsupported syntax type: " + expression.getClass());
    }

    private static double evaluateBinary(BinaryOperator operator, double left, double right) {
        return switch (operator) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> divide(left, right);
            case POW -> Math.pow(left, right);
        };
    }

    private static double divide(double numerator, double denominator) {
        return Math.abs(denominator) < 1e-12 ? Double.NaN : numerator / denominator;
    }

    private static double requireVariable(Map<String, Double> variables, String name) {
        Double value = variables.get(name);
        if (value == null) {
            throw new IllegalArgumentException("missing variable binding: " + name);
        }
        return value;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("value-DAG evaluation experiment is closed");
        }
    }

    public record Comparison(
            double treeValue,
            double dagValue,
            int treeNodeEvaluations,
            int dagValueEvaluations,
            int distinctValues,
            long projectionNanos,
            long planConstructionNanos,
            long treeExecutionNanos,
            long dagExecutionNanos,
            boolean planCacheHit,
            long totalPlanCacheHits,
            long totalPlanCacheMisses,
            long totalPlanCacheEvictions,
            int cachedPlans,
            int internedValues) {
    }

    private enum InstructionKind { NUMBER, VARIABLE, OPERATION }

    private record Instruction(
            InstructionKind kind,
            double number,
            String variable,
            String operator,
            List<Integer> operands,
            List<Integer> multiplicities) {
        private static Instruction number(double value) {
            return new Instruction(InstructionKind.NUMBER, value, "", "", List.of(), List.of());
        }

        private static Instruction variable(String name) {
            return new Instruction(InstructionKind.VARIABLE, 0.0, name, "", List.of(), List.of());
        }

        private static Instruction operation(
                String operator,
                List<Integer> operands,
                List<Integer> multiplicities) {
            return new Instruction(
                    InstructionKind.OPERATION,
                    0.0,
                    "",
                    operator,
                    List.copyOf(operands),
                    List.copyOf(multiplicities));
        }

        private int multiplicity(int index) {
            return multiplicities.isEmpty() ? 1 : multiplicities.get(index);
        }
    }

    private record EvaluationPlan(List<Instruction> instructions, int rootSlot) {
    }

    private record EvaluationRun(
            double value,
            int evaluations,
            int distinctValues,
            long planConstructionNanos,
            long executionNanos,
            boolean planCacheHit) {
    }

    private record Execution(double value, int evaluations) {
    }

    private record TreeExecution(double value, int evaluations) {
    }

    /** Small access-ordered cache reusable by later evaluator or matcher experiments. */
    static final class BoundedValueCache<V> {
        private final int maximumEntries;
        private final LinkedHashMap<ValueKey, V> entries = new LinkedHashMap<>(16, 0.75f, true);
        private long evictions;

        private BoundedValueCache(int maximumEntries) {
            if (maximumEntries < 1) {
                throw new IllegalArgumentException("maximumEntries must be positive");
            }
            this.maximumEntries = maximumEntries;
        }

        private V get(ValueKey key) {
            return entries.get(key);
        }

        private void put(ValueKey key, V value) {
            if (!entries.containsKey(key) && entries.size() >= maximumEntries) {
                ValueKey eldest = entries.keySet().iterator().next();
                entries.remove(eldest);
                evictions++;
            }
            entries.put(key, value);
        }

        private int size() {
            return entries.size();
        }

        private long evictions() {
            return evictions;
        }

        private void clear() {
            entries.clear();
        }
    }
}
