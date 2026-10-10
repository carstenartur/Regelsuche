package de.regelsuche.ast;

import de.regelsuche.scalar.ExactRational;
import java.util.Objects;

/** An exact numeric leaf; source spelling belongs to the parser's occurrence data. */
public record NumberExpr(ExactRational value) implements Expr {
    public NumberExpr {
        Objects.requireNonNull(value, "value");
    }

    public NumberExpr(long value) {
        this(ExactRational.integer(value));
    }

    /** Creates an exact integer, finite decimal or rational from its characters. */
    public static NumberExpr exact(String literal) {
        return new NumberExpr(ExactRational.parse(literal));
    }
    @Override public boolean equals(Object other) {
        if (de.regelsuche.retention.RetainedOperation.isObserved()) return ExpressionIdentity.same(this, other);
        return this == other || other instanceof NumberExpr number && value.equals(number.value);
    }
    @Override public int hashCode() {
        if (de.regelsuche.retention.RetainedOperation.isObserved()) return ExpressionIdentity.valueHash(this);
        return value.hashCode();
    }

}
