package de.regelsuche.value;

import java.util.List;
import java.util.Objects;

/** Immutable value for operators whose operand roles remain ordered. */
public final class OrderedValue extends ExprValue {
    private final ValueOperator operator;
    private final List<ExprValue> operands;

    OrderedValue(ValueOperator operator, List<? extends ExprValue> operands) {
        this(prepare(operator, operands));
    }

    private OrderedValue(Prepared prepared) {
        super(prepared.key());
        this.operator = prepared.operator();
        this.operands = prepared.operands();
    }

    public ValueOperator operator() {
        return operator;
    }

    public List<ExprValue> operands() {
        return operands;
    }

    private static Prepared prepare(ValueOperator operator, List<? extends ExprValue> operands) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(operands, "operands");
        List<ExprValue> copy = List.copyOf(operands);
        if (copy.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("operands must not contain null");
        }
        if (operator.laws().supportsUnorderedNaryValue()) {
            throw new IllegalArgumentException("AC operator must use AssociativeCommutativeValue");
        }
        operator.requireArity(copy.size());
        return new Prepared(operator, copy, ValueKey.ordered(operator, copy));
    }

    private record Prepared(ValueOperator operator, List<ExprValue> operands, ValueKey key) {
    }

    @Override
    public String toString() {
        return operator.id() + operands;
    }
}
