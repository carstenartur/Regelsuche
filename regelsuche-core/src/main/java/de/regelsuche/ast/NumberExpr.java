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
}
