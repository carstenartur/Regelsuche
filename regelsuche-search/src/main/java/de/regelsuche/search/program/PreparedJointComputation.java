package de.regelsuche.search.program;

import de.regelsuche.ast.*;
import java.util.*;

/** Validated immutable topological schedule. Execution values are local to each call. */
public final class PreparedJointComputation {
    public record Cost(long operationWork, long peakLiveStorage, long retainedOutputStorage,
            int outputBindings, int operationCount, long inspectionWork) {
        public long weighted(long workWeight, long liveWeight, long retainedWeight, long outputWeight) {
            if (workWeight < 0 || liveWeight < 0 || retainedWeight < 0 || outputWeight < 0)
                throw new IllegalArgumentException("cost weights must be nonnegative");
            return Math.addExact(Math.addExact(Math.multiplyExact(operationWork, workWeight), Math.multiplyExact(peakLiveStorage, liveWeight)),
                Math.addExact(Math.multiplyExact(retainedOutputStorage, retainedWeight), Math.multiplyExact(outputBindings, outputWeight)));
        }
    }
    public record Node(Expr expression, ComputationBackend.Type type, ComputationBackend.Operation operation,
            List<Integer> arguments, long storage) {
        public Node { arguments = List.copyOf(arguments); }
    }
    private final ComputationBackend backend;
    private final Map<String, ComputationBackend.Type> inputs;
    private final List<Node> nodes;
    private final Map<String, Integer> outputs;
    private final int[] lastUse;
    private final Cost cost;

    private PreparedJointComputation(ComputationBackend backend, Map<String, ComputationBackend.Type> inputs,
            List<Node> nodes, Map<String, Integer> outputs, long inspections) {
        this.backend = backend;
        this.inputs = Map.copyOf(inputs);
        this.nodes = List.copyOf(nodes);
        this.outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        lastUse = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) lastUse[i] = i;
        long edges = 0;
        for (int i = 0; i < nodes.size(); i++) {
            edges += nodes.get(i).arguments().size();
            for (int argument : nodes.get(i).arguments()) lastUse[argument] = i;
        }
        for (int output : outputs.values()) lastUse[output] = nodes.size();
        long live = 0, peak = 0, work = 0;
        int operations = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            live = Math.addExact(live, node.storage());
            peak = Math.max(peak, live);
            if (node.operation() != null) { work = Math.addExact(work, node.operation().work()); operations++; }
            for (int argument : new HashSet<>(node.arguments())) if (lastUse[argument] == i) live -= nodes.get(argument).storage();
            if (lastUse[i] == i) live -= node.storage();
        }
        // Includes schedule initialization, edge use counts, liveness inspection, and output retention.
        cost = new Cost(work, peak, live, outputs.size(), operations, inspections + 3L * nodes.size() + 3L * edges + 2L * outputs.size());
    }
    static PreparedJointComputation prepare(JointComputationPlan plan, ComputationBackend backend) {
        Objects.requireNonNull(backend, "backend");
        var builder = new Builder(plan.inputs(), backend);
        var outputs = new LinkedHashMap<String, Integer>();
        var resolved = plan.resolveOutputs();
        var expressions = resolved.expressions();
        for (int i = 0; i < plan.outputs().size(); i++) {
            var output = plan.outputs().get(i);
            int index = builder.intern(expressions.get(i), 0);
            if (!builder.nodes.get(index).type().equals(output.type())) throw new IllegalArgumentException("output type differs: " + output.name());
            outputs.put(output.name(), index);
        }
        return new PreparedJointComputation(backend, plan.inputs(), builder.nodes, outputs, Math.addExact(builder.inspections, resolved.work()));
    }
    public Cost cost() { return cost; }
    public List<Node> nodes() { return nodes; }
    public Map<String, Integer> outputBindings() { return outputs; }
    public Map<String, Object> execute(Map<String, ?> supplied) {
        var actual = new LinkedHashMap<String, Object>();
        for (var input : inputs.entrySet()) {
            Object value = supplied.get(input.getKey());
            input.getValue().requireValue(value);
            actual.put(input.getKey(), value);
        }
        backend.validateInputs(Collections.unmodifiableMap(actual));
        var values = new Object[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            var node = nodes.get(i);
            Object value;
            if (node.expression() instanceof VariableExpr variable) value = actual.get(variable.name());
            else if (node.expression() instanceof NumberExpr number) value = backend.literal(number);
            else value = backend.apply(node.operation(), node.arguments().stream().map(index -> values[index]).toList());
            node.type().requireValue(value);
            values[i] = value;
            for (int argument : node.arguments()) if (lastUse[argument] == i) values[argument] = null;
            if (lastUse[i] == i) values[i] = null;
        }
        var result = new LinkedHashMap<String, Object>();
        outputs.forEach((name, index) -> result.put(name, values[index]));
        return Collections.unmodifiableMap(result);
    }
    private static final class Builder {
        private final Map<String, ComputationBackend.Type> inputs;
        private final ComputationBackend backend;
        private final Map<Expr, Integer> byIdentity = new IdentityHashMap<>();
        private final Map<StructuralKey, Integer> indices = new HashMap<>();
        private final List<Node> nodes = new ArrayList<>();
        private long inspections;
        Builder(Map<String, ComputationBackend.Type> inputs, ComputationBackend backend) { this.inputs = inputs; this.backend = backend; }
        int intern(Expr expression, int depth) {
            charge(1);
            if (depth > JointComputationPlan.MAX_DEPTH) throw new IllegalArgumentException("plan structural limit exceeded");
            Integer existing = byIdentity.get(expression);
            if (existing != null) return existing;
            var arguments = new ArrayList<Integer>();
            for (var child : JointComputationPlan.children(expression)) arguments.add(intern(child, depth + 1));
            // No recursive Expr.hashCode/equals is invoked: parents only refer to already interned integer IDs.
            // Charge construction, lookup, and insertion key-field/edge work before any structural-key hashing.
            charge(3L * (2 + arguments.size()));
            var key = StructuralKey.of(expression, arguments);
            existing = indices.get(key);
            if (existing != null) {
                byIdentity.put(expression, existing);
                return existing;
            }
            ComputationBackend.Type type;
            ComputationBackend.Operation operation = null;
            long storage;
            if (expression instanceof VariableExpr variable) type = Objects.requireNonNull(inputs.get(variable.name()), "unbound input");
            else if (expression instanceof NumberExpr number) type = backend.literalType(number);
            else {
                operation = Objects.requireNonNull(backend.operation(expression), "operation");
                if (arguments.size() != operation.arguments().size()) throw new IllegalArgumentException("operator arity differs");
                for (int i = 0; i < arguments.size(); i++) if (!nodes.get(arguments.get(i)).type().equals(operation.arguments().get(i)))
                    throw new IllegalArgumentException("operator argument type differs");
                type = operation.result();
            }
            storage = operation == null ? backend.leafStorage(type) : operation.storage();
            if (storage < 0) throw new IllegalArgumentException("negative storage");
            int index = nodes.size();
            nodes.add(new Node(expression, type, operation, arguments, storage));
            indices.put(key, index);
            byIdentity.put(expression, index);
            return index;
        }
        private void charge(long work) {
            inspections = Math.addExact(inspections, work);
            if (inspections > JointComputationPlan.MAX_NODES) throw new IllegalArgumentException("plan preparation work bound exceeded");
        }
        private record StructuralKey(String kind, Object value, List<Integer> arguments) {
            StructuralKey { arguments = List.copyOf(arguments); }
            static StructuralKey of(Expr expression, List<Integer> arguments) {
                if (expression instanceof VariableExpr v) return new StructuralKey("variable", v.name(), arguments);
                if (expression instanceof NumberExpr n) return new StructuralKey("number", n.value(), arguments);
                if (expression instanceof BinaryExpr b) return new StructuralKey("binary", b.operator(), arguments);
                return new StructuralKey("function", ((FunctionExpr) expression).name(), arguments);
            }
        }
    }
}
