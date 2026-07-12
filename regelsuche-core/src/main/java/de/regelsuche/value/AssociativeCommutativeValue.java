package de.regelsuche.value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable unordered n-ary value with significant operand multiplicity. */
public final class AssociativeCommutativeValue extends ExprValue {
    private final ValueOperator operator;
    private final Map<ExprValue, Integer> multiplicities;
    private final int operandCount;

    AssociativeCommutativeValue(ValueOperator operator, Map<? extends ExprValue, Integer> multiplicities) {
        this(prepare(operator, multiplicities));
    }

    private AssociativeCommutativeValue(Prepared prepared) {
        super(prepared.key());
        this.operator = prepared.operator();
        this.multiplicities = prepared.multiplicities();
        this.operandCount = prepared.operandCount();
    }

    public ValueOperator operator() {
        return operator;
    }

    /** Unordered value-to-count mapping. Iteration order is not semantic. */
    public Map<ExprValue, Integer> multiplicities() {
        return multiplicities;
    }

    public int operandCount() {
        return operandCount;
    }

    public int multiplicityOf(ExprValue value) {
        return multiplicities.getOrDefault(value, 0);
    }

    private static Prepared prepare(
            ValueOperator operator,
            Map<? extends ExprValue, Integer> multiplicities) {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(multiplicities, "multiplicities");
        if (!operator.laws().supportsUnorderedNaryValue()) {
            throw new IllegalArgumentException("operator is not associative and commutative: " + operator.id());
        }

        Map<ExprValue, Integer> copy = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<? extends ExprValue, Integer> entry : multiplicities.entrySet()) {
            ExprValue value = Objects.requireNonNull(entry.getKey(), "operand value");
            Integer multiplicity = Objects.requireNonNull(entry.getValue(), "operand multiplicity");
            if (multiplicity < 1) {
                throw new IllegalArgumentException("operand multiplicity must be positive");
            }
            copy.merge(value, multiplicity, Math::addExact);
            count = Math.addExact(count, multiplicity);
        }
        operator.requireArity(count);
        Map<ExprValue, Integer> immutable = Collections.unmodifiableMap(copy);
        return new Prepared(
                operator,
                immutable,
                count,
                ValueKey.associativeCommutative(operator, immutable));
    }

    private record Prepared(
            ValueOperator operator,
            Map<ExprValue, Integer> multiplicities,
            int operandCount,
            ValueKey key) {
    }

    @Override
    public String toString() {
        return operator.id() + multiplicities;
    }
}
