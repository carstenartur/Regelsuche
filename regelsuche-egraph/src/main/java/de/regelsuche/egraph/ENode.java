package de.regelsuche.egraph;

import de.regelsuche.ast.BinaryOperator;
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
        if (isArithmeticAcSymbol(symbol)) {
            if (children.size() < 2) throw new IllegalArgumentException("AC nodes require at least two operands");
            // Hash-cons a multiset of canonical class IDs. Equal IDs retain every occurrence.
            children = children.stream().sorted().toList();
        }
    }

    static boolean isArithmeticAcSymbol(String symbol) {
        return symbol.equals("ac:ADD") || symbol.equals("ac:MUL");
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
            return graph.bindAssumptions(graph.find(direct), safeAssumptions);
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
                validateOrderedOperator(ordered);
                List<EClassId> childIds = ordered.operands().stream().map(this::addValueNodes).toList();
                added = graph.add(new ENode(symbol(ordered.operator()), childIds));
            } else if (value instanceof AssociativeCommutativeValue acValue) {
                added = addAcValue(acValue);
            } else {
                throw new IllegalArgumentException(
                    "unsupported ExprValue type: " + value.getClass().getName());
            }
            EClassId canonical = graph.find(added);
            classesByValue.put(value.key(), canonical);
            return canonical;
        }

        private EClassId addAcValue(AssociativeCommutativeValue value) {
            if (graph.usesArithmeticAc()) requireStandardOperator(value.operator(), value.operandCount());
            List<Map.Entry<ExprValue, Integer>> entries = new ArrayList<>(value.multiplicities().entrySet());
            entries.sort(Map.Entry.comparingByKey(Comparator.comparing(ExprValue::key)));
            List<EClassId> children = new ArrayList<>(value.operandCount());
            for (var entry : entries) {
                children.addAll(Collections.nCopies(entry.getValue(), addValueNodes(entry.getKey())));
            }
            if (graph.usesArithmeticAc()) {
                return graph.add(new ENode("ac:" + binaryOperator(value.operator()).name(), children));
            }
            String nodeSymbol = symbol(value.operator());
            EClassId result = children.getFirst();
            for (int i = 1; i < children.size(); i++) {
                result = graph.add(new ENode(nodeSymbol, List.of(result, children.get(i))));
            }
            return result;
        }

        private void validateOrderedOperator(OrderedValue value) {
            if (graph.usesArithmeticAc()) {
                requireStandardOperator(value.operator(), value.operands().size());
            } else if (!value.operator().id().startsWith("fn:") && value.operands().size() != 2) {
                throw new IllegalArgumentException("Ordered binary operator requires two operands: " + value.operator().id());
            }
        }

        private static void requireStandardOperator(ValueOperator operator, int arity) {
            ValueOperator standard = switch (operator.id()) {
                case "add" -> ValueOperator.ADD;
                case "mul" -> ValueOperator.MUL;
                case "sub" -> ValueOperator.SUB;
                case "div" -> ValueOperator.DIV;
                case "pow" -> ValueOperator.POW;
                default -> operator.id().startsWith("fn:")
                    ? ValueOperator.function(operator.id().substring(3), arity) : null;
            };
            if (!operator.equals(standard)) {
                throw new IllegalArgumentException("Unsupported operator laws for the exact scalar profile: " + operator);
            }
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

        private static String format(de.regelsuche.scalar.ExactRational value) {
            return value.canonicalText();
        }
    }
}
