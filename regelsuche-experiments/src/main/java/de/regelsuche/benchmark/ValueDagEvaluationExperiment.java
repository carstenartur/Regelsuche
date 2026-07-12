package de.regelsuche.benchmark;

import de.regelsuche.ast.Expr;
import de.regelsuche.value.ExprValueFactory;
import de.regelsuche.value.ExprValueFactory.AssociativeCommutativeValue;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.NumberValue;
import de.regelsuche.value.ExprValueFactory.OrderedValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import de.regelsuche.value.ExprValueFactory.VariableValue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded value-DAG evaluator and plan-cache experiment for issue #251. */
public final class ValueDagEvaluationExperiment implements AutoCloseable {
    private final ExprValueFactory values;
    private final int maximumPlans;
    private final LinkedHashMap<ValueKey, Plan> plans = new LinkedHashMap<>(16, 0.75f, true);
    private long hits;
    private long misses;
    private long evictions;
    private boolean closed;

    public ValueDagEvaluationExperiment(int maximumValues, int maximumPlans) {
        values = new ExprValueFactory(maximumValues);
        if (maximumPlans < 1) {
            throw new IllegalArgumentException("maximumPlans must be positive");
        }
        this.maximumPlans = maximumPlans;
    }

    public synchronized Result evaluate(Expr syntax, Map<String, Double> variables) {
        ensureOpen();
        Objects.requireNonNull(syntax, "syntax");
        Map<String, Double> bindings = Map.copyOf(Objects.requireNonNull(variables, "variables"));

        long projectionStarted = System.nanoTime();
        ExprValue root = values.project(syntax).valueRoot();
        long projectionNanos = System.nanoTime() - projectionStarted;

        Plan plan = plans.get(root.key());
        boolean cacheHit = plan != null;
        long constructionNanos = 0L;
        if (cacheHit) {
            hits++;
        } else {
            misses++;
            long started = System.nanoTime();
            plan = compile(root);
            constructionNanos = System.nanoTime() - started;
            cache(root.key(), plan);
        }

        long executionStarted = System.nanoTime();
        double result = execute(plan, bindings);
        long executionNanos = System.nanoTime() - executionStarted;
        return new Result(
                result,
                plan.steps().size(),
                projectionNanos,
                constructionNanos,
                executionNanos,
                cacheHit,
                hits,
                misses,
                evictions,
                plans.size(),
                values.size());
    }

    public synchronized int cachedPlanCount() {
        ensureOpen();
        return plans.size();
    }

    @Override
    public synchronized void close() {
        plans.clear();
        values.close();
        closed = true;
    }

    private void cache(ValueKey key, Plan plan) {
        if (!plans.containsKey(key) && plans.size() >= maximumPlans) {
            plans.remove(plans.keySet().iterator().next());
            evictions++;
        }
        plans.put(key, plan);
    }

    private static Plan compile(ExprValue root) {
        Map<ValueKey, Integer> slots = new LinkedHashMap<>();
        List<Step> steps = new ArrayList<>();
        int rootSlot = compile(root, slots, steps);
        return new Plan(List.copyOf(steps), rootSlot);
    }

    private static int compile(ExprValue value, Map<ValueKey, Integer> slots, List<Step> steps) {
        Integer existing = slots.get(value.key());
        if (existing != null) {
            return existing;
        }
        Step step;
        if (value instanceof NumberValue number) {
            step = Step.number(number.value());
        } else if (value instanceof VariableValue variable) {
            step = Step.variable(variable.name());
        } else if (value instanceof OrderedValue ordered) {
            List<Integer> operands = ordered.operands().stream()
                    .map(operand -> compile(operand, slots, steps))
                    .toList();
            step = Step.operation(ordered.operator().id(), operands, List.of());
        } else if (value instanceof AssociativeCommutativeValue ac) {
            List<Map.Entry<ExprValue, Integer>> entries = new ArrayList<>(ac.multiplicities().entrySet());
            entries.sort(Comparator.comparing(entry -> entry.getKey().key()));
            List<Integer> operands = new ArrayList<>(entries.size());
            List<Integer> counts = new ArrayList<>(entries.size());
            for (Map.Entry<ExprValue, Integer> entry : entries) {
                operands.add(compile(entry.getKey(), slots, steps));
                counts.add(entry.getValue());
            }
            step = Step.operation(ac.operator().id(), operands, counts);
        } else {
            throw new IllegalArgumentException("unsupported value type: " + value.getClass());
        }
        int slot = steps.size();
        steps.add(step);
        slots.put(value.key(), slot);
        return slot;
    }

    private static double execute(Plan plan, Map<String, Double> variables) {
        double[] results = new double[plan.steps().size()];
        for (int slot = 0; slot < plan.steps().size(); slot++) {
            Step step = plan.steps().get(slot);
            results[slot] = switch (step.kind()) {
                case NUMBER -> step.number();
                case VARIABLE -> requireVariable(variables, step.variable());
                case OPERATION -> operation(step, results);
            };
        }
        return results[plan.rootSlot()];
    }

    private static double operation(Step step, double[] results) {
        List<Integer> operands = step.operands();
        return switch (step.operator()) {
            case "add" -> aggregate(step, results, 0.0, true);
            case "mul" -> aggregate(step, results, 1.0, false);
            case "sub" -> results[operands.get(0)] - results[operands.get(1)];
            case "div" -> divide(results[operands.get(0)], results[operands.get(1)]);
            case "pow" -> Math.pow(results[operands.get(0)], results[operands.get(1)]);
            default -> function(step.operator(), results[operands.getFirst()]);
        };
    }

    private static double aggregate(Step step, double[] results, double initial, boolean sum) {
        double value = initial;
        for (int i = 0; i < step.operands().size(); i++) {
            double operand = results[step.operands().get(i)];
            int multiplicity = step.multiplicity(i);
            value = sum ? value + operand * multiplicity : value * Math.pow(operand, multiplicity);
        }
        return value;
    }

    private static double function(String operator, double argument) {
        if (!operator.startsWith("fn:")) {
            throw new IllegalArgumentException("unsupported operator: " + operator);
        }
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

    public record Result(
            double value,
            int evaluationCount,
            long projectionNanos,
            long planConstructionNanos,
            long executionNanos,
            boolean planCacheHit,
            long totalPlanCacheHits,
            long totalPlanCacheMisses,
            long totalPlanCacheEvictions,
            int cachedPlans,
            int internedValues) {
        public int distinctValues() {
            return evaluationCount;
        }
    }

    private enum Kind { NUMBER, VARIABLE, OPERATION }

    private record Step(
            Kind kind,
            double number,
            String variable,
            String operator,
            List<Integer> operands,
            List<Integer> multiplicities) {
        private static Step number(double value) {
            return new Step(Kind.NUMBER, value, "", "", List.of(), List.of());
        }

        private static Step variable(String name) {
            return new Step(Kind.VARIABLE, 0.0, name, "", List.of(), List.of());
        }

        private static Step operation(String operator, List<Integer> operands, List<Integer> counts) {
            return new Step(Kind.OPERATION, 0.0, "", operator, List.copyOf(operands), List.copyOf(counts));
        }

        private int multiplicity(int index) {
            return multiplicities.isEmpty() ? 1 : multiplicities.get(index);
        }
    }

    private record Plan(List<Step> steps, int rootSlot) {
    }
}
