package de.regelsuche.architecture.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Executable vertical spikes for ADR #242.
 *
 * <p>The test compares two representations without changing the production AST:</p>
 * <ol>
 *   <li>a separate semantic AST plus explicit forward/reverse projection, and</li>
 *   <li>a scoped, interned value DAG whose occurrence nodes refer directly to values.</li>
 * </ol>
 */
class ExpressionIdentitySpikeTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void bothSpikesImplementAcValueEqualityButOnlyInterningProvidesValueIdentity() {
        List<String> variants = List.of("(a + b) + c", "a + (b + c)", "c + a + b");

        DualProjector dualProjector = new DualProjector();
        List<Value> dualRoots = variants.stream()
            .map(parser::parseTerm)
            .map(dualProjector::project)
            .map(DualProjection::semanticRoot)
            .toList();

        assertEquals(dualRoots.get(0), dualRoots.get(1));
        assertEquals(dualRoots.get(0), dualRoots.get(2));
        assertNotSame(dualRoots.get(0), dualRoots.get(1),
            "a second semantic tree still allocates equal values separately");

        ValueFactory factory = new ValueFactory();
        ValueGraphProjector valueProjector = new ValueGraphProjector(factory);
        List<ValueGraph> graphs = variants.stream()
            .map(parser::parseTerm)
            .map(valueProjector::project)
            .toList();

        assertSame(graphs.get(0).root().value(), graphs.get(1).root().value());
        assertSame(graphs.get(0).root().value(), graphs.get(2).root().value(),
            "the scoped factory gives AC-equivalent sums one value identity");
    }

    @Test
    void occurrenceSetPreservesTwoUsesOfOneInternedVariable() {
        ValueFactory factory = new ValueFactory();
        ValueGraph graph = new ValueGraphProjector(factory).project(parser.parseTerm("a + a + b"));
        Value a = factory.variable("a");

        Set<Occurrence> aUses = graph.usesOf(a);
        assertEquals(2, aUses.size(),
            "a normal Set is sufficient when its elements are occurrence objects with unique IDs");
        assertEquals(2, aUses.stream().map(Occurrence::id).distinct().count());
        assertTrue(aUses.stream().allMatch(use -> use.value() == a));

        Set<Value> distinctValues = new LinkedHashSet<>();
        aUses.forEach(use -> distinctValues.add(use.value()));
        assertEquals(1, distinctValues.size(),
            "the value set and the occurrence set intentionally answer different questions");

        AcValue sum = assertInstanceOf(AcValue.class, graph.root().value());
        assertEquals(2, sum.multiplicities().get(a));
        assertEquals(1, sum.multiplicities().get(factory.variable("b")));
    }

    @Test
    void sharedSubexpressionHasTwoUsesButOneValue() {
        Expr syntax = parser.parseTerm("(a + b) * (a + b)");

        DualProjection dual = new DualProjector().project(syntax);
        Value dualLeft = dual.forward().get(List.of(0));
        Value dualRight = dual.forward().get(List.of(1));
        assertEquals(dualLeft, dualRight);
        assertNotSame(dualLeft, dualRight,
            "the dual-tree spike duplicates an equal semantic subexpression");

        AcValue dualRoot = assertInstanceOf(AcValue.class, dual.semanticRoot());
        Value rootOperand = dualRoot.multiplicities().keySet().iterator().next();
        assertTrue(rootOperand == dualLeft || rootOperand == dualRight);
        assertFalse(rootOperand == dualLeft && rootOperand == dualRight,
            "one duplicated semantic node is not the object referenced by the canonical root");

        ValueFactory factory = new ValueFactory();
        ValueGraph graph = new ValueGraphProjector(factory).project(syntax);
        Occurrence leftUse = graph.at(List.of(0));
        Occurrence rightUse = graph.at(List.of(1));

        assertNotEquals(leftUse.id(), rightUse.id());
        assertSame(leftUse.value(), rightUse.value());
        assertEquals(2, graph.usesOf(leftUse.value()).size());
        assertEquals(4, factory.poolSize(),
            "expected values: a, b, a+b, and (a+b)*(a+b)");
    }

    @Test
    void interningReducesValueObjectsAcrossAnAcCorpus() {
        List<String> variants = List.of("(a + b) + c", "a + (b + c)", "c + a + b");

        DualProjector dual = new DualProjector();
        int dualSemanticAllocations = variants.stream()
            .map(parser::parseTerm)
            .map(dual::project)
            .mapToInt(DualProjection::semanticAllocations)
            .sum();

        ValueFactory factory = new ValueFactory();
        ValueGraphProjector projector = new ValueGraphProjector(factory);
        List<ValueGraph> graphs = variants.stream()
            .map(parser::parseTerm)
            .map(projector::project)
            .toList();
        int occurrenceCount = graphs.stream().mapToInt(ValueGraph::occurrenceCount).sum();

        assertEquals(15, dualSemanticAllocations);
        assertEquals(15, occurrenceCount,
            "all concrete written occurrences remain available");
        assertEquals(7, factory.poolSize(),
            "three atoms, three distinct partial sums, and one shared full sum");
        assertSame(graphs.get(0).root().value(), graphs.get(1).root().value());
        assertSame(graphs.get(0).root().value(), graphs.get(2).root().value());
    }

    @Test
    void scopedReferenceIdentityHasAStableCrossScopeKey() {
        ValueFactory firstFactory = new ValueFactory();
        ValueFactory secondFactory = new ValueFactory();

        Value first = firstFactory.fromExpr(parser.parseTerm("(a + b) + c"));
        Value second = secondFactory.fromExpr(parser.parseTerm("c + a + b"));

        assertNotSame(first, second,
            "reference identity is deliberately scoped to one factory");
        assertEquals(first, second,
            "structural value equality remains valid across factory scopes");
        assertEquals(first.stableKey(), second.stableKey(),
            "persistence and cross-scope comparison use a stable structural key");
    }

    @Test
    void aLocalRewriteTargetsAnOccurrenceRatherThanTheSharedValue() {
        ValueFactory factory = new ValueFactory();
        Expr original = parser.parseTerm("a + a");
        ValueGraph graph = new ValueGraphProjector(factory).project(original);
        Value a = factory.variable("a");
        List<Occurrence> uses = graph.usesOf(a).stream()
            .sorted(Comparator.comparing(Occurrence::path, ExpressionIdentitySpikeTest::comparePaths))
            .toList();

        Expr rewritten = replaceAt(original, uses.getFirst().path(), new VariableExpr("b"));

        assertEquals(2, countVariable(original, "a"));
        assertEquals(1, countVariable(rewritten, "a"));
        assertEquals(1, countVariable(rewritten, "b"));
        assertEquals(2, graph.usesOf(a).size(),
            "the original immutable graph and its two uses are unchanged");
    }

    @Test
    void dagEvaluationComputesOneSharedValueOnce() {
        Expr syntax = parser.parseTerm("(a + b) * (a + b)");
        Map<String, Double> variables = Map.of("a", 2.0, "b", 3.0);

        CountingTreeEvaluator treeEvaluator = new CountingTreeEvaluator(variables);
        double treeResult = treeEvaluator.evaluate(syntax);

        ValueFactory factory = new ValueFactory();
        Value root = factory.fromExpr(syntax);
        CountingDagEvaluator dagEvaluator = new CountingDagEvaluator(variables);
        double dagResult = dagEvaluator.evaluate(root);

        assertEquals(25.0, treeResult);
        assertEquals(treeResult, dagResult);
        assertEquals(7, treeEvaluator.evaluations());
        assertEquals(4, dagEvaluator.evaluations(),
            "a, b, a+b, and the product are each evaluated once");
    }

    private static int comparePaths(List<Integer> left, List<Integer> right) {
        int common = Math.min(left.size(), right.size());
        for (int i = 0; i < common; i++) {
            int comparison = Integer.compare(left.get(i), right.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static Expr replaceAt(Expr expression, List<Integer> path, Expr replacement) {
        if (path.isEmpty()) {
            return replacement;
        }
        int child = path.getFirst();
        List<Integer> remainder = path.subList(1, path.size());
        if (expression instanceof BinaryExpr binary) {
            return switch (child) {
                case 0 -> new BinaryExpr(replaceAt(binary.left(), remainder, replacement),
                    binary.operator(), binary.right());
                case 1 -> new BinaryExpr(binary.left(), binary.operator(),
                    replaceAt(binary.right(), remainder, replacement));
                default -> throw new IllegalArgumentException("binary child index must be 0 or 1");
            };
        }
        if (expression instanceof FunctionExpr function) {
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(child, replaceAt(arguments.get(child), remainder, replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException("path descends through a leaf: " + path);
    }

    private static int countVariable(Expr expression, String name) {
        if (expression instanceof VariableExpr variable) {
            return variable.name().equals(name) ? 1 : 0;
        }
        if (expression instanceof BinaryExpr binary) {
            return countVariable(binary.left(), name) + countVariable(binary.right(), name);
        }
        if (expression instanceof FunctionExpr function) {
            return function.arguments().stream().mapToInt(argument -> countVariable(argument, name)).sum();
        }
        return 0;
    }

    private interface Value {
        String stableKey();
    }

    private record AtomValue(String stableKey) implements Value {
        private AtomValue {
            if (stableKey == null || stableKey.isBlank()) {
                throw new IllegalArgumentException("stableKey must not be blank");
            }
        }
    }

    private record AcValue(BinaryOperator operator, Map<Value, Integer> multiplicities) implements Value {
        private AcValue {
            if (operator != BinaryOperator.ADD && operator != BinaryOperator.MUL) {
                throw new IllegalArgumentException("AC value requires ADD or MUL");
            }
            multiplicities = Map.copyOf(multiplicities);
            if (multiplicities.isEmpty()) {
                throw new IllegalArgumentException("AC value needs at least one operand");
            }
            if (multiplicities.values().stream().anyMatch(count -> count == null || count < 1)) {
                throw new IllegalArgumentException("multiplicities must be positive");
            }
        }

        @Override
        public String stableKey() {
            return operator.name() + multiplicities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(Value::stableKey)))
                .map(entry -> entry.getKey().stableKey() + "*" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
    }

    private record OrderedValue(BinaryOperator operator, List<Value> operands) implements Value {
        private OrderedValue {
            operands = List.copyOf(operands);
            if (operator == BinaryOperator.ADD || operator == BinaryOperator.MUL) {
                throw new IllegalArgumentException("ADD and MUL must use AcValue");
            }
            if (operands.size() != 2) {
                throw new IllegalArgumentException("binary value needs two operands");
            }
        }

        @Override
        public String stableKey() {
            return operator.name() + "(" + operands.get(0).stableKey() + "," + operands.get(1).stableKey() + ")";
        }
    }

    private record FunctionValue(String name, List<Value> arguments) implements Value {
        private FunctionValue {
            arguments = List.copyOf(arguments);
        }

        @Override
        public String stableKey() {
            return "F:" + name + arguments.stream()
                .map(Value::stableKey)
                .collect(java.util.stream.Collectors.joining(",", "(", ")"));
        }
    }

    private static final class ValueFactory {
        private final Map<Value, Value> pool = new HashMap<>();

        Value variable(String name) {
            return intern(new AtomValue("V:" + name));
        }

        Value number(double value) {
            return intern(new AtomValue("N:" + Double.toString(value)));
        }

        Value binary(BinaryOperator operator, Value left, Value right) {
            if (operator == BinaryOperator.ADD || operator == BinaryOperator.MUL) {
                Map<Value, Integer> multiplicities = new HashMap<>();
                addAcOperand(operator, left, multiplicities);
                addAcOperand(operator, right, multiplicities);
                return intern(new AcValue(operator, multiplicities));
            }
            return intern(new OrderedValue(operator, List.of(left, right)));
        }

        Value function(String name, List<Value> arguments) {
            return intern(new FunctionValue(name, arguments));
        }

        Value fromExpr(Expr expression) {
            if (expression instanceof VariableExpr variable) {
                return variable(variable.name());
            }
            if (expression instanceof NumberExpr number) {
                return number(number.value());
            }
            if (expression instanceof BinaryExpr binary) {
                return binary(binary.operator(), fromExpr(binary.left()), fromExpr(binary.right()));
            }
            if (expression instanceof FunctionExpr function) {
                return function(function.name(), function.arguments().stream().map(this::fromExpr).toList());
            }
            throw new IllegalArgumentException("unsupported expression: " + expression);
        }

        int poolSize() {
            return pool.size();
        }

        @SuppressWarnings("unchecked")
        private <T extends Value> T intern(T candidate) {
            return (T) pool.computeIfAbsent(candidate, ignored -> candidate);
        }
    }

    private static void addAcOperand(BinaryOperator operator, Value operand,
                                     Map<Value, Integer> multiplicities) {
        if (operand instanceof AcValue acValue && acValue.operator() == operator) {
            acValue.multiplicities().forEach(
                (value, count) -> multiplicities.merge(value, count, Integer::sum));
        } else {
            multiplicities.merge(operand, 1, Integer::sum);
        }
    }

    private static final class DualProjector {
        DualProjection project(Expr syntaxRoot) {
            Map<List<Integer>, Value> forward = new LinkedHashMap<>();
            Map<Value, Set<List<Integer>>> reverse = new IdentityHashMap<>();
            int[] allocations = {0};
            Value root = project(syntaxRoot, List.of(), forward, reverse, allocations);
            return new DualProjection(syntaxRoot, root, Map.copyOf(forward), reverse, allocations[0]);
        }

        private Value project(Expr expression, List<Integer> path,
                              Map<List<Integer>, Value> forward,
                              Map<Value, Set<List<Integer>>> reverse,
                              int[] allocations) {
            Value value;
            if (expression instanceof VariableExpr variable) {
                value = allocate(new AtomValue("V:" + variable.name()), allocations);
            } else if (expression instanceof NumberExpr number) {
                value = allocate(new AtomValue("N:" + Double.toString(number.value())), allocations);
            } else if (expression instanceof BinaryExpr binary) {
                Value left = project(binary.left(), childPath(path, 0), forward, reverse, allocations);
                Value right = project(binary.right(), childPath(path, 1), forward, reverse, allocations);
                if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.MUL) {
                    Map<Value, Integer> multiplicities = new HashMap<>();
                    addAcOperand(binary.operator(), left, multiplicities);
                    addAcOperand(binary.operator(), right, multiplicities);
                    value = allocate(new AcValue(binary.operator(), multiplicities), allocations);
                } else {
                    value = allocate(new OrderedValue(binary.operator(), List.of(left, right)), allocations);
                }
            } else if (expression instanceof FunctionExpr function) {
                List<Value> arguments = new ArrayList<>();
                for (int index = 0; index < function.arguments().size(); index++) {
                    arguments.add(project(function.arguments().get(index), childPath(path, index),
                        forward, reverse, allocations));
                }
                value = allocate(new FunctionValue(function.name(), arguments), allocations);
            } else {
                throw new IllegalArgumentException("unsupported expression: " + expression);
            }

            List<Integer> stablePath = List.copyOf(path);
            forward.put(stablePath, value);
            reverse.computeIfAbsent(value, ignored -> new LinkedHashSet<>()).add(stablePath);
            return value;
        }

        private static Value allocate(Value value, int[] allocations) {
            allocations[0]++;
            return value;
        }
    }

    private record DualProjection(Expr syntaxRoot, Value semanticRoot,
                                  Map<List<Integer>, Value> forward,
                                  Map<Value, Set<List<Integer>>> reverse,
                                  int semanticAllocations) {
    }

    private static final class ValueGraphProjector {
        private final ValueFactory factory;
        private long nextId = 1;

        private ValueGraphProjector(ValueFactory factory) {
            this.factory = factory;
        }

        ValueGraph project(Expr syntaxRoot) {
            Occurrence root = project(syntaxRoot, List.of());
            Map<Value, Set<Occurrence>> mutableIndex = new HashMap<>();
            index(root, mutableIndex);
            Map<Value, Set<Occurrence>> frozenIndex = new HashMap<>();
            mutableIndex.forEach((value, uses) -> frozenIndex.put(value, Set.copyOf(uses)));
            return new ValueGraph(syntaxRoot, root, Map.copyOf(frozenIndex));
        }

        private Occurrence project(Expr expression, List<Integer> path) {
            List<Occurrence> children = new ArrayList<>();
            Value value;
            if (expression instanceof VariableExpr variable) {
                value = factory.variable(variable.name());
            } else if (expression instanceof NumberExpr number) {
                value = factory.number(number.value());
            } else if (expression instanceof BinaryExpr binary) {
                Occurrence left = project(binary.left(), childPath(path, 0));
                Occurrence right = project(binary.right(), childPath(path, 1));
                children.add(left);
                children.add(right);
                value = factory.binary(binary.operator(), left.value(), right.value());
            } else if (expression instanceof FunctionExpr function) {
                for (int index = 0; index < function.arguments().size(); index++) {
                    children.add(project(function.arguments().get(index), childPath(path, index)));
                }
                value = factory.function(function.name(), children.stream().map(Occurrence::value).toList());
            } else {
                throw new IllegalArgumentException("unsupported expression: " + expression);
            }
            return new Occurrence(nextId++, List.copyOf(path), value, List.copyOf(children));
        }

        private static void index(Occurrence occurrence, Map<Value, Set<Occurrence>> index) {
            index.computeIfAbsent(occurrence.value(), ignored -> new LinkedHashSet<>()).add(occurrence);
            occurrence.children().forEach(child -> index(child, index));
        }
    }

    private record Occurrence(long id, List<Integer> path, Value value,
                              List<Occurrence> children) {
        int count() {
            return 1 + children.stream().mapToInt(Occurrence::count).sum();
        }
    }

    private record ValueGraph(Expr syntaxRoot, Occurrence root,
                              Map<Value, Set<Occurrence>> usesByValue) {
        Set<Occurrence> usesOf(Value value) {
            return usesByValue.getOrDefault(value, Set.of());
        }

        Occurrence at(List<Integer> path) {
            Occurrence current = root;
            for (int child : path) {
                current = current.children().get(child);
            }
            return current;
        }

        int occurrenceCount() {
            return root.count();
        }
    }

    private static List<Integer> childPath(List<Integer> parent, int child) {
        List<Integer> path = new ArrayList<>(parent);
        path.add(child);
        return List.copyOf(path);
    }

    private static final class CountingTreeEvaluator {
        private final Map<String, Double> variables;
        private int evaluations;

        private CountingTreeEvaluator(Map<String, Double> variables) {
            this.variables = variables;
        }

        double evaluate(Expr expression) {
            evaluations++;
            if (expression instanceof VariableExpr variable) {
                return variables.get(variable.name());
            }
            if (expression instanceof NumberExpr number) {
                return number.value();
            }
            if (expression instanceof BinaryExpr binary) {
                double left = evaluate(binary.left());
                double right = evaluate(binary.right());
                return evaluateBinary(binary.operator(), left, right);
            }
            if (expression instanceof FunctionExpr function) {
                double argument = evaluate(function.argument());
                return evaluateFunction(function.name(), argument);
            }
            throw new IllegalArgumentException("unsupported expression: " + expression);
        }

        int evaluations() {
            return evaluations;
        }
    }

    private static final class CountingDagEvaluator {
        private final Map<String, Double> variables;
        private final Map<Value, Double> memo = new IdentityHashMap<>();
        private int evaluations;

        private CountingDagEvaluator(Map<String, Double> variables) {
            this.variables = variables;
        }

        double evaluate(Value value) {
            Double cached = memo.get(value);
            if (cached != null) {
                return cached;
            }
            evaluations++;
            double result;
            if (value instanceof AtomValue atom) {
                if (atom.stableKey().startsWith("V:")) {
                    result = variables.get(atom.stableKey().substring(2));
                } else {
                    result = Double.parseDouble(atom.stableKey().substring(2));
                }
            } else if (value instanceof AcValue acValue) {
                if (acValue.operator() == BinaryOperator.ADD) {
                    result = acValue.multiplicities().entrySet().stream()
                        .mapToDouble(entry -> evaluate(entry.getKey()) * entry.getValue())
                        .sum();
                } else {
                    result = 1.0;
                    for (Map.Entry<Value, Integer> entry : acValue.multiplicities().entrySet()) {
                        result *= Math.pow(evaluate(entry.getKey()), entry.getValue());
                    }
                }
            } else if (value instanceof OrderedValue ordered) {
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

        int evaluations() {
            return evaluations;
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
