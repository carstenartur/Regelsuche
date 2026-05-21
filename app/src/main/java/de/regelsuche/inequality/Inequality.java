package de.regelsuche.inequality;

import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.Objects;

/**
 * Inequality of two {@link Expr} trees connected by a {@link Comparator}.
 */
public record Inequality(Expr left, Comparator comparator, Expr right) {
    public Inequality {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(comparator, "comparator");
        Objects.requireNonNull(right, "right");
    }

    public String formatted() {
        return ExpressionFormatter.format(left) + " " + comparator.symbol() + " " + ExpressionFormatter.format(right);
    }
}
