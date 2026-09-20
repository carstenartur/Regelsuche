package de.regelsuche.search.program;

import de.regelsuche.ast.*;
import java.util.*;

/** Immutable typed definitions and explicitly named outputs over the existing AST.
 * Repeated structural subexpressions become one node when prepared, including across outputs.
 * Definitions are ordinary AST variable references, resolved before entering the existing frontier.
 */
public final class JointComputationPlan {
    public static final String OUTPUTS = "jointoutputs";
    public static final String OUTPUT = "jointoutput";
    public static final int MAX_NODES = 10_000;
    public static final int MAX_DEPTH = 128;

    public record Output(String name, ComputationBackend.Type type, Expr expression) {
        public Output {
            requireName(name);
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(expression, "expression");
        }
    }
    private final Map<String, ComputationBackend.Type> inputs;
    private final Map<String, Expr> definitions;
    private final List<Output> outputs;

    public JointComputationPlan(Map<String, ComputationBackend.Type> inputs,
            Map<String, Expr> definitions, List<Output> outputs) {
        this.inputs = Map.copyOf(inputs);
        this.definitions = Map.copyOf(definitions);
        this.outputs = List.copyOf(outputs);
        if (outputs.isEmpty()) throw new IllegalArgumentException("at least one output required");
        this.inputs.keySet().forEach(JointComputationPlan::requireName);
        this.definitions.keySet().forEach(JointComputationPlan::requireName);
        if (!Collections.disjoint(this.inputs.keySet(), this.definitions.keySet()))
            throw new IllegalArgumentException("definition shadows input");
        var names = new HashSet<String>();
        for (var output : this.outputs) if (!names.add(output.name())) throw new IllegalArgumentException("duplicate output");
    }
    public Map<String, ComputationBackend.Type> inputs() { return inputs; }
    public List<Output> outputs() { return outputs; }
    public PreparedJointComputation prepare(ComputationBackend backend) {
        return PreparedJointComputation.prepare(this, backend);
    }
    public List<Expr> outputExpressions() {
        return resolveOutputs().expressions();
    }
    /** Tree size is saturated at MAX_NODES + 1, computed from DAG edges without expanding them. */
    public record Resolution(List<Expr> expressions, long work, int expandedNodes, int height) {
        public Resolution { expressions = List.copyOf(expressions); }
    }
    public Resolution resolveOutputs() {
        var resolver = new Resolver();
        for (var name : definitions.keySet()) resolver.resolve(new VariableExpr(name), 0);
        var expressions = new ArrayList<Expr>();
        int expandedNodes = 0, height = 0;
        for (var output : outputs) {
            var resolved = resolver.resolve(output.expression(), 0);
            expressions.add(resolved.expression());
            expandedNodes = saturatedAdd(expandedNodes, resolved.expandedNodes());
            height = Math.max(height, resolved.height());
        }
        return new Resolution(expressions, resolver.visits + outputs.size(), expandedNodes, height);
    }
    /** Canonical envelope for TypedMoveSearch. Names are data; only output values are computations. */
    public Expr expression() {
        return searchExpression().expression();
    }
    public record SearchExpression(Expr expression, long work) {}
    public SearchExpression searchExpression() {
        var resolved = resolveOutputs();
        if ((long) resolved.expandedNodes() + 2L * outputs.size() + 1 > MAX_NODES || resolved.height() + 2 > MAX_DEPTH)
            throw new IllegalArgumentException("expanded frontier AST exceeds transport bound");
        var expressions = resolved.expressions();
        var bindings = new ArrayList<Expr>();
        for (int i = 0; i < outputs.size(); i++) bindings.add(new FunctionExpr(OUTPUT,
            List.of(new VariableExpr(outputs.get(i).name()), expressions.get(i))));
        return new SearchExpression(new FunctionExpr(OUTPUTS, bindings), resolved.work() + 2L * outputs.size() + 1);
    }
    public JointComputationPlan withExpression(Expr expression) {
        if (!(expression instanceof FunctionExpr all) || !all.name().equals(OUTPUTS)
                || all.arguments().size() != outputs.size()) throw new IllegalArgumentException("output envelope differs");
        var next = new ArrayList<Output>();
        for (int i = 0; i < outputs.size(); i++) {
            if (!(all.arguments().get(i) instanceof FunctionExpr binding) || !binding.name().equals(OUTPUT)
                    || binding.arguments().size() != 2 || !(binding.arguments().getFirst() instanceof VariableExpr name)
                    || !name.name().equals(outputs.get(i).name())) throw new IllegalArgumentException("output binding differs");
            next.add(new Output(name.name(), outputs.get(i).type(), binding.arguments().get(1)));
        }
        return new JointComputationPlan(inputs, Map.of(), next);
    }
    public JointComputationPlan withOutputs(List<Expr> expressions) {
        if (expressions.size() != outputs.size()) throw new IllegalArgumentException("output count differs");
        var next = new ArrayList<Output>();
        for (int i = 0; i < outputs.size(); i++) next.add(new Output(outputs.get(i).name(), outputs.get(i).type(), expressions.get(i)));
        return new JointComputationPlan(inputs, Map.of(), next);
    }
    static List<Expr> children(Expr expression) {
        if (expression instanceof BinaryExpr binary) return List.of(binary.left(), binary.right());
        if (expression instanceof FunctionExpr function) return function.arguments();
        return List.of();
    }
    private static void requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("binding name required");
    }
    private static int saturatedAdd(int left, int right) { return Math.min(MAX_NODES + 1, left + right); }
    private record Resolved(Expr expression, int height, int expandedNodes) {}
    private final class Resolver {
        private final Map<String, Resolved> resolved = new HashMap<>();
        private final Map<Expr, Resolved> byIdentity = new IdentityHashMap<>();
        private final Set<String> visiting = new HashSet<>();
        private int visits;
        Resolved resolve(Expr expression, int depth) {
            if (++visits > MAX_NODES || depth > MAX_DEPTH) throw new IllegalArgumentException("plan structural limit exceeded");
            var cached = byIdentity.get(expression);
            if (cached != null) return bounded(cached, depth);
            Resolved result;
            if (expression instanceof VariableExpr variable) {
                String name = variable.name();
                if (inputs.containsKey(name)) result = new Resolved(variable, 0, 1);
                else if (resolved.containsKey(name)) result = resolved.get(name);
                else {
                    if (!definitions.containsKey(name)) throw new IllegalArgumentException("unbound reference: " + name);
                    if (!visiting.add(name)) throw new IllegalArgumentException("cyclic definition: " + name);
                    result = resolve(definitions.get(name), depth + 1);
                    visiting.remove(name);
                    resolved.put(name, result);
                }
            } else if (expression instanceof BinaryExpr binary) {
                var left = resolve(binary.left(), depth + 1);
                var right = resolve(binary.right(), depth + 1);
                result = new Resolved(new BinaryExpr(left.expression(), binary.operator(), right.expression()),
                    1 + Math.max(left.height(), right.height()), saturatedAdd(1, saturatedAdd(left.expandedNodes(), right.expandedNodes())));
            } else if (expression instanceof FunctionExpr function) {
                var arguments = new ArrayList<Expr>();
                int height = 0, expandedNodes = 1;
                for (Expr argument : function.arguments()) {
                    var child = resolve(argument, depth + 1);
                    arguments.add(child.expression());
                    height = Math.max(height, child.height() + 1);
                    expandedNodes = saturatedAdd(expandedNodes, child.expandedNodes());
                }
                result = new Resolved(new FunctionExpr(function.name(), arguments), height, expandedNodes);
            } else {
                result = new Resolved(expression, 0, 1);
            }
            bounded(result, depth);
            byIdentity.put(expression, result);
            return result;
        }
        private Resolved bounded(Resolved result, int depth) {
            if (depth + result.height() > MAX_DEPTH) throw new IllegalArgumentException("plan structural depth exceeded");
            return result;
        }
    }
}
