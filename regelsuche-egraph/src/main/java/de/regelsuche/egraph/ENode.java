package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.value.ExprValueFactory.AssociativeCommutativeValue;
import de.regelsuche.value.ExprValueFactory.ExprValue;
import de.regelsuche.value.ExprValueFactory.NumberValue;
import de.regelsuche.value.ExprValueFactory.OrderedValue;
import de.regelsuche.value.ExprValueFactory.ValueKey;
import de.regelsuche.value.ExprValueFactory.ValueOperator;
import de.regelsuche.value.ExprValueFactory.VariableValue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One node in an {@link EGraph}: a symbol (operator name, function name,
 * variable name, or numeric literal) together with the e-class IDs of its
 * children.
 *
 * <p>An e-node is the analogue of an AST node, but children are referenced
 * via {@link EClassId}s rather than by direct pointer. Two e-nodes are
 * structurally equal when they share the same {@code symbol} and the same
 * sequence of <em>canonical</em> child e-class IDs — which is how
 * {@link EGraph#rebuild()} discovers congruence ("if {@code a≡b} then
 * {@code f(a)≡f(b)}").</p>
 *
 * <p>This record is immutable; the e-graph achieves congruence closure by
 * <em>replacing</em> stale e-nodes via {@link #canonicalize}, not by
 * mutating them in place.</p>
 */
public record ENode(String symbol, List<EClassId> children) {

    public ENode {
        if (symbol == null || symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol must not be empty");
        }
        children = children == null ? List.of() : List.copyOf(children);
    }

    /** Convenience constructor for leaves (variables, numbers). */
    public static ENode leaf(String symbol) {
        return new ENode(symbol, List.of());
    }

    /**
     * Return a copy of this node with every child replaced by its current
     * canonical (find-root) e-class ID under {@code unionFind}.
     */
    public ENode canonicalize(UnionFind unionFind) {
        if (children.isEmpty()) {
            return this;
        }
        EClassId[] canonical = new EClassId[children.size()];
        boolean changed = false;
        for (int i = 0; i < children.size(); i++) {
            EClassId before = children.get(i);
            EClassId after = unionFind.find(before);
            canonical[i] = after;
            if (!Objects.equals(before, after)) {
                changed = true;
            }
        }
        if (!changed) {
            return this;
        }
        return new ENode(symbol, List.of(canonical));
    }

    /** {@code true} if this node has no children. */
    public boolean isLeaf() {
        return children.isEmpty();
    }

    /** Read-only view of the child references. */
    public List<EClassId> childView() {
        return Collections.unmodifiableList(children);
    }

    /** Owner-scoped bridge; ValueKey caches values while EClassId remains graph identity. */
    public static final class ExprValueAdapter {
        private final EGraph graph;
        private final Map<ValueKey, EClassId> classesByValue = new HashMap<>();

        public ExprValueAdapter(EGraph graph) {
            this.graph = Objects.requireNonNull(graph, "graph");
        }

        public EClassId add(ExprValue value) {
            return add(value, List.of());
        }

        /** Adds a value while preserving the existing assumption-conflict contract. */
        public EClassId add(ExprValue value, List<String> assumptions) {
            Objects.requireNonNull(value, "value");
            List<String> safeAssumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            EClassId direct = addValueNodes(value);
            EClassId checked = graph.addExpression(toExpression(value), safeAssumptions);
            EClassId directRoot = graph.find(direct);
            if (!directRoot.equals(graph.find(checked))) {
                throw new IllegalStateException("ExprValue and AST insertion produced different e-classes");
            }
            classesByValue.put(value.key(), directRoot);
            return directRoot;
        }

        public Optional<EClassId> classFor(ValueKey key) {
            EClassId id = classesByValue.get(Objects.requireNonNull(key, "key"));
            return id == null ? Optional.empty() : Optional.of(graph.find(id));
        }

        public int mappedValueCount() {
            return classesByValue.size();
        }

        private EClassId addValueNodes(ExprValue value) {
            EClassId existing = classesByValue.get(value.key());
            if (existing != null) {
                return graph.find(existing);
            }
            EClassId added;
            if (value instanceof VariableValue variable) {
                added = graph.add(leaf("var:" + variable.name()));
            } else if (value instanceof NumberValue number) {
                added = graph.add(leaf("num:" + format(number.value())));
            } else if (value instanceof OrderedValue ordered) {
                List<EClassId> childIds = ordered.operands().stream().map(this::addValueNodes).toList();
                added = graph.add(new ENode(symbol(ordered.operator()), childIds));
            } else if (value instanceof AssociativeCommutativeValue acValue) {
                List<ExprValue> operands = expandedOperands(acValue);
                EClassId result = addValueNodes(operands.getFirst());
                String nodeSymbol = symbol(acValue.operator());
                for (int i = 1; i < operands.size(); i++) {
                    result = graph.add(new ENode(
                        nodeSymbol,
                        List.of(result, addValueNodes(operands.get(i)))));
                }
                added = result;
            } else {
                throw new IllegalArgumentException(
                    "unsupported ExprValue type: " + value.getClass().getName());
            }
            EClassId canonical = graph.find(added);
            classesByValue.put(value.key(), canonical);
            return canonical;
        }

        private Expr toExpression(ExprValue value) {
            if (value instanceof VariableValue variable) {
                return new VariableExpr(variable.name());
            }
            if (value instanceof NumberValue number) {
                return new NumberExpr(number.value());
            }
            if (value instanceof OrderedValue ordered) {
                List<Expr> operands = ordered.operands().stream().map(this::toExpression).toList();
                if (ordered.operator().id().startsWith("fn:")) {
                    return new FunctionExpr(ordered.operator().id().substring(3), operands);
                }
                if (operands.size() != 2) {
                    throw new IllegalArgumentException(
                        "ordered non-function value must have exactly two operands: "
                            + ordered.operator().id());
                }
                return new BinaryExpr(
                    operands.get(0), binaryOperator(ordered.operator()), operands.get(1));
            }
            if (value instanceof AssociativeCommutativeValue acValue) {
                List<ExprValue> operands = expandedOperands(acValue);
                Expr result = toExpression(operands.getFirst());
                BinaryOperator operator = binaryOperator(acValue.operator());
                for (int i = 1; i < operands.size(); i++) {
                    result = new BinaryExpr(result, operator, toExpression(operands.get(i)));
                }
                return result;
            }
            throw new IllegalArgumentException(
                "unsupported ExprValue type: " + value.getClass().getName());
        }

        private static List<ExprValue> expandedOperands(AssociativeCommutativeValue value) {
            List<Map.Entry<ExprValue, Integer>> entries =
                new ArrayList<>(value.multiplicities().entrySet());
            entries.sort(Comparator.comparing(entry -> entry.getKey().key()));
            List<ExprValue> expanded = new ArrayList<>(value.operandCount());
            for (Map.Entry<ExprValue, Integer> entry : entries) {
                for (int i = 0; i < entry.getValue(); i++) {
                    expanded.add(entry.getKey());
                }
            }
            return List.copyOf(expanded);
        }

        private static String symbol(ValueOperator operator) {
            return operator.id().startsWith("fn:")
                ? operator.id()
                : "op:" + binaryOperator(operator).name();
        }

        private static BinaryOperator binaryOperator(ValueOperator operator) {
            try {
                return BinaryOperator.valueOf(operator.id().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "unsupported value operator: " + operator.id(), exception);
            }
        }

        private static String format(double value) {
            if (value == Math.floor(value) && !Double.isInfinite(value)) {
                return Long.toString((long) value);
            }
            return Double.toString(value);
        }
    }
}
