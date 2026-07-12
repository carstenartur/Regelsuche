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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owner-scoped bridge from immutable mathematical values to e-graph nodes.
 *
 * <p>{@link ValueKey} is used only as the adapter cache key. The resulting graph
 * identity remains an independent {@link EClassId}; the two identity domains are
 * deliberately never represented by the same type.</p>
 */
public final class ExprValueEGraphAdapter {
    private final EGraph graph;
    private final Map<ValueKey, EClassId> classesByValue = new HashMap<>();

    public ExprValueEGraphAdapter(EGraph graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    public EClassId add(ExprValue value) {
        return add(value, List.of());
    }

    /**
     * Adds a value and applies assumptions to its root e-class using the same
     * conflict rules as the existing AST insertion API.
     */
    public EClassId add(ExprValue value, List<String> assumptions) {
        Objects.requireNonNull(value, "value");
        List<String> safeAssumptions = assumptions == null ? List.of() : List.copyOf(assumptions);

        EClassId direct = addValueNodes(value);
        EClassId checked = graph.addExpression(toExpression(value), safeAssumptions);
        EClassId directRoot = graph.find(direct);
        EClassId checkedRoot = graph.find(checked);
        if (!directRoot.equals(checkedRoot)) {
            throw new IllegalStateException("ExprValue and AST insertion produced different e-classes");
        }
        classesByValue.put(value.key(), directRoot);
        return directRoot;
    }

    public Optional<EClassId> classFor(ValueKey key) {
        Objects.requireNonNull(key, "key");
        EClassId id = classesByValue.get(key);
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
            added = graph.add(ENode.leaf("var:" + variable.name()));
        } else if (value instanceof NumberValue number) {
            added = graph.add(ENode.leaf("num:" + format(number.value())));
        } else if (value instanceof OrderedValue ordered) {
            List<EClassId> children = ordered.operands().stream()
                .map(this::addValueNodes)
                .toList();
            added = graph.add(new ENode(symbol(ordered.operator()), children));
        } else if (value instanceof AssociativeCommutativeValue acValue) {
            List<ExprValue> operands = expandedOperands(acValue);
            EClassId result = addValueNodes(operands.getFirst());
            String symbol = symbol(acValue.operator());
            for (int i = 1; i < operands.size(); i++) {
                result = graph.add(new ENode(symbol, List.of(result, addValueNodes(operands.get(i)))));
            }
            added = result;
        } else {
            throw new IllegalArgumentException("unsupported ExprValue type: " + value.getClass().getName());
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
            String operatorId = ordered.operator().id();
            if (operatorId.startsWith("fn:")) {
                return new FunctionExpr(operatorId.substring(3), operands);
            }
            return new BinaryExpr(
                operands.get(0),
                binaryOperator(ordered.operator()),
                operands.get(1));
        }
        if (value instanceof AssociativeCommutativeValue acValue) {
            List<ExprValue> values = expandedOperands(acValue);
            Expr result = toExpression(values.getFirst());
            BinaryOperator operator = binaryOperator(acValue.operator());
            for (int i = 1; i < values.size(); i++) {
                result = new BinaryExpr(result, operator, toExpression(values.get(i)));
            }
            return result;
        }
        throw new IllegalArgumentException("unsupported ExprValue type: " + value.getClass().getName());
    }

    private static List<ExprValue> expandedOperands(AssociativeCommutativeValue value) {
        List<Map.Entry<ExprValue, Integer>> entries = new ArrayList<>(value.multiplicities().entrySet());
        entries.sort(Map.Entry.comparingByKey(
            java.util.Comparator.comparing(ExprValue::key)));
        List<ExprValue> expanded = new ArrayList<>(value.operandCount());
        for (Map.Entry<ExprValue, Integer> entry : entries) {
            for (int i = 0; i < entry.getValue(); i++) {
                expanded.add(entry.getKey());
            }
        }
        return List.copyOf(expanded);
    }

    private static String symbol(ValueOperator operator) {
        String id = operator.id();
        if (id.startsWith("fn:")) {
            return id;
        }
        return "op:" + binaryOperator(operator).name();
    }

    private static BinaryOperator binaryOperator(ValueOperator operator) {
        try {
            return BinaryOperator.valueOf(operator.id().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported value operator: " + operator.id(), exception);
        }
    }

    private static String format(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
