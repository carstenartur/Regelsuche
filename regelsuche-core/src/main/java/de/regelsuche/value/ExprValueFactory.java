package de.regelsuche.value;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Owner-scoped hash-consing factory for immutable mathematical expression values.
 *
 * <p>The factory is deliberately not global. Equal values are reference-identical
 * inside one factory scope; {@link ValueKey} remains authoritative across scopes
 * and persistence boundaries.</p>
 */
public final class ExprValueFactory implements AutoCloseable {
    public static final int DEFAULT_MAXIMUM_ENTRIES = 100_000;

    private final int maximumEntries;
    private final Map<ValueKey, ExprValue> valuesByKey = new HashMap<>();
    private boolean closed;

    public ExprValueFactory() {
        this(DEFAULT_MAXIMUM_ENTRIES);
    }

    public ExprValueFactory(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    public synchronized VariableValue variable(String name) {
        return intern(new VariableValue(name), VariableValue.class);
    }

    public synchronized NumberValue number(double value) {
        return intern(new NumberValue(value), NumberValue.class);
    }

    public ExprValue sum(List<? extends ExprValue> operands) {
        return associativeCommutative(ValueOperator.ADD, operands);
    }

    public ExprValue product(List<? extends ExprValue> operands) {
        return associativeCommutative(ValueOperator.MUL, operands);
    }

    public synchronized ExprValue ordered(
            ValueOperator operator,
            List<? extends ExprValue> operands) {
        ensureOpen();
        Objects.requireNonNull(operator, "operator");
        if (operator.laws().supportsUnorderedNaryValue()) {
            return associativeCommutative(operator, operands);
        }
        return intern(new OrderedValue(operator, operands), OrderedValue.class);
    }

    public ExprValue function(String name, List<? extends ExprValue> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return ordered(ValueOperator.function(name, arguments.size()), arguments);
    }

    /** Projects one syntax expression into this factory scope. */
    public ExprValue fromExpr(Expr expression) {
        Objects.requireNonNull(expression, "expression");
        return fromExprRecursive(expression, null);
    }

    /** Returns the stable mathematical value key for one syntax expression. */
    public ValueKey keyOf(Expr expression) {
        return fromExpr(expression).key();
    }

    /** Projects a root and retains syntax-object-identity to value links. */
    public ExprValueProjection project(Expr syntaxRoot) {
        Objects.requireNonNull(syntaxRoot, "syntaxRoot");
        IdentityHashMap<Expr, ExprValue> valuesBySyntax = new IdentityHashMap<>();
        ExprValue valueRoot = fromExprRecursive(syntaxRoot, valuesBySyntax);
        return new ExprValueProjection(syntaxRoot, valueRoot, valuesBySyntax);
    }

    public synchronized Optional<ExprValue> find(ValueKey key) {
        ensureOpen();
        return Optional.ofNullable(valuesByKey.get(Objects.requireNonNull(key, "key")));
    }

    public synchronized int size() {
        ensureOpen();
        return valuesByKey.size();
    }

    public int maximumEntries() {
        return maximumEntries;
    }

    public synchronized void clear() {
        ensureOpen();
        valuesByKey.clear();
    }

    @Override
    public synchronized void close() {
        valuesByKey.clear();
        closed = true;
    }

    private ExprValue fromExprRecursive(
            Expr expression,
            IdentityHashMap<Expr, ExprValue> valuesBySyntax) {
        if (valuesBySyntax != null) {
            ExprValue existing = valuesBySyntax.get(expression);
            if (existing != null) {
                return existing;
            }
        }

        ExprValue value;
        if (expression instanceof VariableExpr variable) {
            value = variable(variable.name());
        } else if (expression instanceof NumberExpr number) {
            value = number(number.value());
        } else if (expression instanceof FunctionExpr function) {
            List<ExprValue> arguments = new ArrayList<>(function.arguments().size());
            for (Expr argument : function.arguments()) {
                arguments.add(fromExprRecursive(argument, valuesBySyntax));
            }
            value = function(function.name(), arguments);
        } else if (expression instanceof BinaryExpr binary) {
            ExprValue left = fromExprRecursive(binary.left(), valuesBySyntax);
            ExprValue right = fromExprRecursive(binary.right(), valuesBySyntax);
            value = switch (binary.operator()) {
                case ADD -> sum(List.of(left, right));
                case SUB -> ordered(ValueOperator.SUB, List.of(left, right));
                case MUL -> product(List.of(left, right));
                case DIV -> ordered(ValueOperator.DIV, List.of(left, right));
                case POW -> ordered(ValueOperator.POW, List.of(left, right));
            };
        } else {
            throw new IllegalArgumentException("unsupported Expr implementation: " + expression.getClass());
        }

        if (valuesBySyntax != null) {
            valuesBySyntax.put(expression, value);
        }
        return value;
    }

    private synchronized ExprValue associativeCommutative(
            ValueOperator operator,
            List<? extends ExprValue> operands) {
        ensureOpen();
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operands, "operands");
        if (!operator.laws().supportsUnorderedNaryValue()) {
            throw new IllegalArgumentException("operator is not associative and commutative: " + operator.id());
        }
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("AC operation needs at least one operand");
        }

        Map<ExprValue, Integer> multiplicities = new LinkedHashMap<>();
        for (ExprValue operand : operands) {
            ExprValue value = Objects.requireNonNull(operand, "operand");
            if (value instanceof AssociativeCommutativeValue nested
                    && nested.operator().equals(operator)) {
                for (Map.Entry<ExprValue, Integer> entry : nested.multiplicities().entrySet()) {
                    multiplicities.merge(entry.getKey(), entry.getValue(), Math::addExact);
                }
            } else {
                multiplicities.merge(value, 1, Math::addExact);
            }
        }

        int count = multiplicities.values().stream().mapToInt(Integer::intValue).sum();
        if (count == 1) {
            return multiplicities.keySet().iterator().next();
        }
        operator.requireArity(count);
        return intern(
                new AssociativeCommutativeValue(operator, multiplicities),
                AssociativeCommutativeValue.class);
    }

    private <T extends ExprValue> T intern(T candidate, Class<T> expectedType) {
        ensureOpen();
        ExprValue existing = valuesByKey.get(candidate.key());
        if (existing != null) {
            return expectedType.cast(existing);
        }
        if (valuesByKey.size() >= maximumEntries) {
            throw new IllegalStateException(
                    "expression value factory capacity exceeded: " + maximumEntries);
        }
        valuesByKey.put(candidate.key(), candidate);
        return candidate;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("expression value factory is closed");
        }
    }
}
