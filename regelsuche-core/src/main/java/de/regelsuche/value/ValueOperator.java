package de.regelsuche.value;

import java.util.Objects;

/** Operator identity and the laws used when constructing immutable expression values. */
public record ValueOperator(
        String id,
        int minimumArity,
        int maximumArity,
        OperatorLaws laws) {

    public static final ValueOperator ADD =
            new ValueOperator("add", 2, Integer.MAX_VALUE, OperatorLaws.ASSOCIATIVE_COMMUTATIVE);
    public static final ValueOperator SUB =
            new ValueOperator("sub", 2, 2, OperatorLaws.NONE);
    public static final ValueOperator MUL =
            new ValueOperator("mul", 2, Integer.MAX_VALUE, OperatorLaws.ASSOCIATIVE_COMMUTATIVE);
    public static final ValueOperator DIV =
            new ValueOperator("div", 2, 2, OperatorLaws.NONE);
    public static final ValueOperator POW =
            new ValueOperator("pow", 2, 2, OperatorLaws.NONE);

    public ValueOperator {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(laws, "laws");
        id = id.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("operator id must not be blank");
        }
        if (minimumArity < 0 || maximumArity < minimumArity) {
            throw new IllegalArgumentException("invalid operator arity range");
        }
    }

    public static ValueOperator function(String name, int arity) {
        Objects.requireNonNull(name, "name");
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("function name must not be blank");
        }
        if (arity < 0) {
            throw new IllegalArgumentException("function arity must not be negative");
        }
        return new ValueOperator("fn:" + normalized, arity, arity, OperatorLaws.NONE);
    }

    public void requireArity(int actualArity) {
        if (actualArity < minimumArity || actualArity > maximumArity) {
            throw new IllegalArgumentException(
                    "operator " + id + " expects arity " + minimumArity + ".." + maximumArity
                            + " but got " + actualArity);
        }
    }
}
