package de.regelsuche.benchmark;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Micro-benchmarks supporting ADR #242. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class ExpressionIdentityBenchmarks {
    private List<Expr> acCorpus;
    private ValueFactory warmFactory;
    private Expr repeatedSyntax;
    private Value repeatedValue;
    private Map<String, Double> variables;

    @Setup
    public void setup() {
        ExpressionParser parser = new ExpressionParser();
        List<String> variants = List.of(
            "(a + b) + c",
            "a + (b + c)",
            "c + a + b",
            "(a + b) * (a + b)",
            "(x + y) * (x + y)",
            "a + a + b"
        );
        List<Expr> expressions = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            expressions.add(parser.parseTerm(variants.get(i % variants.size())));
        }
        acCorpus = List.copyOf(expressions);

        warmFactory = new ValueFactory();
        acCorpus.forEach(warmFactory::fromExpr);

        String repeatedTerm = "(a + b) * (a + b)";
        repeatedSyntax = parser.parseTerm(String.join(" + ", java.util.Collections.nCopies(32, repeatedTerm)));
        repeatedValue = new ValueFactory().fromExpr(repeatedSyntax);
        variables = Map.of("a", 2.0, "b", 3.0);
    }

    @Benchmark
    public int dualSemanticProjectionAllocatesPerOccurrence() {
        int checksum = 0;
        for (Expr expression : acCorpus) {
            checksum = 31 * checksum + projectWithoutInterning(expression).hashCode();
        }
        return checksum;
    }

    @Benchmark
    public int internedProjectionWithFreshScope() {
        ValueFactory factory = new ValueFactory();
        int checksum = 0;
        for (Expr expression : acCorpus) {
            checksum = 31 * checksum + factory.fromExpr(expression).hashCode();
        }
        return checksum + factory.poolSize();
    }

    @Benchmark
    public int internedProjectionWithWarmScope() {
        int checksum = 0;
        for (Expr expression : acCorpus) {
            checksum = 31 * checksum + System.identityHashCode(warmFactory.fromExpr(expression));
        }
        return checksum;
    }

    @Benchmark
    public double treeEvaluationOfRepeatedSubexpressions() {
        return evaluateTree(repeatedSyntax, variables);
    }

    @Benchmark
    public double dagEvaluationOfRepeatedSubexpressions() {
        return new DagEvaluator(variables).evaluate(repeatedValue);
    }

    private static Value projectWithoutInterning(Expr expression) {
        if (expression instanceof VariableExpr variable) {
            return new Atom("V:" + variable.name());
        }
        if (expression instanceof NumberExpr number) {
            return new Atom("N:" + number.value());
        }
        if (expression instanceof BinaryExpr binary) {
            Value left = projectWithoutInterning(binary.left());
            Value right = projectWithoutInterning(binary.right());
            return createValue(binary.operator(), left, right);
        }
        if (expression instanceof FunctionExpr function) {
            return new FunctionValue(function.name(),
                function.arguments().stream().map(ExpressionIdentityBenchmarks::projectWithoutInterning).toList());
        }
        throw new IllegalArgumentException("unsupported expression: " + expression);
    }

    private static Value createValue(BinaryOperator operator, Value left, Value right) {
        if (operator == BinaryOperator.ADD || operator == BinaryOperator.MUL) {
            Map<Value, Integer> multiplicities = new HashMap<>();
            addOperand(operator, left, multiplicities);
            addOperand(operator, right, multiplicities);
            return new Ac(operator, Map.copyOf(multiplicities));
        }
        return new Ordered(operator, List.of(left, right));
    }

    private static void addOperand(BinaryOperator operator, Value operand,
                                   Map<Value, Integer> multiplicities) {
        if (operand instanceof Ac ac && ac.operator() == operator) {
            ac.multiplicities().forEach(
                (value, count) -> multiplicities.merge(value, count, Integer::sum));
        } else {
            multiplicities.merge(operand, 1, Integer::sum);
        }
    }

    private interface Value {
    }

    private record Atom(String key) implements Value {
    }

    private record Ac(BinaryOperator operator, Map<Value, Integer> multiplicities) implements Value {
    }

    private record Ordered(BinaryOperator operator, List<Value> operands) implements Value {
    }

    private record FunctionValue(String name, List<Value> arguments) implements Value {
    }

    private static final class ValueFactory {
        private final Map<Value, Value> pool = new HashMap<>();

        Value fromExpr(Expr expression) {
            if (expression instanceof VariableExpr variable) {
                return intern(new Atom("V:" + variable.name()));
            }
            if (expression instanceof NumberExpr number) {
                return intern(new Atom("N:" + number.value()));
            }
            if (expression instanceof BinaryExpr binary) {
                Value left = fromExpr(binary.left());
                Value right = fromExpr(binary.right());
                return intern(createValue(binary.operator(), left, right));
            }
            if (expression instanceof FunctionExpr function) {
                return intern(new FunctionValue(function.name(),
                    function.arguments().stream().map(this::fromExpr).toList()));
            }
            throw new IllegalArgumentException("unsupported expression: " + expression);
        }

        int poolSize() {
            return pool.size();
        }

        private Value intern(Value candidate) {
            return pool.computeIfAbsent(candidate, ignored -> candidate);
        }
    }

    private static double evaluateTree(Expr expression, Map<String, Double> variables) {
        if (expression instanceof VariableExpr variable) {
            return variables.get(variable.name());
        }
        if (expression instanceof NumberExpr number) {
            return number.value();
        }
        if (expression instanceof BinaryExpr binary) {
            return evaluateBinary(binary.operator(), evaluateTree(binary.left(), variables),
                evaluateTree(binary.right(), variables));
        }
        if (expression instanceof FunctionExpr function) {
            return evaluateFunction(function.name(), evaluateTree(function.argument(), variables));
        }
        throw new IllegalArgumentException("unsupported expression: " + expression);
    }

    private static final class DagEvaluator {
        private final Map<String, Double> variables;
        private final Map<Value, Double> memo = new IdentityHashMap<>();

        private DagEvaluator(Map<String, Double> variables) {
            this.variables = variables;
        }

        double evaluate(Value value) {
            Double cached = memo.get(value);
            if (cached != null) {
                return cached;
            }
            double result;
            if (value instanceof Atom atom) {
                result = atom.key().startsWith("V:")
                    ? variables.get(atom.key().substring(2))
                    : Double.parseDouble(atom.key().substring(2));
            } else if (value instanceof Ac ac) {
                if (ac.operator() == BinaryOperator.ADD) {
                    result = ac.multiplicities().entrySet().stream()
                        .mapToDouble(entry -> evaluate(entry.getKey()) * entry.getValue())
                        .sum();
                } else {
                    result = 1.0;
                    for (Map.Entry<Value, Integer> entry : ac.multiplicities().entrySet()) {
                        result *= Math.pow(evaluate(entry.getKey()), entry.getValue());
                    }
                }
            } else if (value instanceof Ordered ordered) {
                result = evaluateBinary(ordered.operator(), evaluate(ordered.operands().get(0)),
                    evaluate(ordered.operands().get(1)));
            } else if (value instanceof FunctionValue function) {
                result = evaluateFunction(function.name(), evaluate(function.arguments().getFirst()));
            } else {
                throw new IllegalArgumentException("unsupported value: " + value);
            }
            memo.put(value, result);
            return result;
        }
    }

    private static double evaluateBinary(BinaryOperator operator, double left, double right) {
        return switch (operator) {
            case ADD -> left + right;
            case SUB -> left - right;
            case MUL -> left * right;
            case DIV -> left / right;
            case POW -> Math.pow(left, right);
        };
    }

    private static double evaluateFunction(String name, double argument) {
        return switch (name) {
            case "sin" -> Math.sin(argument);
            case "cos" -> Math.cos(argument);
            case "tan" -> Math.tan(argument);
            case "sqrt" -> Math.sqrt(argument);
            case "exp" -> Math.exp(argument);
            case "abs" -> Math.abs(argument);
            case "log", "ln" -> Math.log(argument);
            default -> throw new IllegalArgumentException("unsupported function: " + name);
        };
    }
}
