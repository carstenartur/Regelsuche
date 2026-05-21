package de.regelsuche.inequality;

import de.regelsuche.assumption.Assumption;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.parse.ExpressionFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Atomic transformation rules for {@link Inequality}.
 *
 * <p>Two fundamental rules are provided:</p>
 * <ul>
 *   <li>{@link #addBothSides(Inequality, Expr)} — adding the same term on
 *       both sides preserves the comparator unconditionally.</li>
 *   <li>{@link #multiplyBothSides(Inequality, Expr)} — multiplying by a
 *       constant: the comparator stays the same for a strictly positive
 *       factor, <em>flips</em> for a strictly negative factor, and the
 *       (degenerate) zero case is rejected because it collapses the
 *       inequality to {@code 0 ⋈ 0}. For non-literal multipliers the
 *       transformation surfaces an assumption capturing the sign that the
 *       caller must justify.</li>
 * </ul>
 */
public final class InequalityRewriteEngine {

    public InequalityStep addBothSides(Inequality inequality, Expr term) {
        Objects.requireNonNull(inequality, "inequality");
        Objects.requireNonNull(term, "term");
        Inequality next = new Inequality(
            new BinaryExpr(inequality.left(), BinaryOperator.ADD, term),
            inequality.comparator(),
            new BinaryExpr(inequality.right(), BinaryOperator.ADD, term)
        );
        return new InequalityStep(
            "inequality_add_both_sides",
            next,
            "Addiere " + ExpressionFormatter.format(term) + " auf beiden Seiten",
            List.of()
        );
    }

    public InequalityStep subtractBothSides(Inequality inequality, Expr term) {
        Objects.requireNonNull(inequality, "inequality");
        Objects.requireNonNull(term, "term");
        Inequality next = new Inequality(
            new BinaryExpr(inequality.left(), BinaryOperator.SUB, term),
            inequality.comparator(),
            new BinaryExpr(inequality.right(), BinaryOperator.SUB, term)
        );
        return new InequalityStep(
            "inequality_subtract_both_sides",
            next,
            "Subtrahiere " + ExpressionFormatter.format(term) + " auf beiden Seiten",
            List.of()
        );
    }

    /**
     * Multiply both sides by {@code factor}. Flips the comparator if the
     * factor is a strictly negative numeric literal.
     *
     * @throws IllegalArgumentException if {@code factor} is the numeric
     *     literal {@code 0} (multiplication would erase the inequality).
     */
    public InequalityStep multiplyBothSides(Inequality inequality, Expr factor) {
        Objects.requireNonNull(inequality, "inequality");
        Objects.requireNonNull(factor, "factor");
        if (factor instanceof NumberExpr number && number.value() == 0.0) {
            throw new IllegalArgumentException("Cannot multiply an inequality by zero");
        }
        Sign sign = signOf(factor);
        Comparator nextComparator = sign == Sign.NEGATIVE
            ? inequality.comparator().flip()
            : inequality.comparator();
        Inequality next = new Inequality(
            new BinaryExpr(inequality.left(), BinaryOperator.MUL, factor),
            nextComparator,
            new BinaryExpr(inequality.right(), BinaryOperator.MUL, factor)
        );
        String description = sign == Sign.NEGATIVE
            ? "Multipliziere beide Seiten mit " + ExpressionFormatter.format(factor)
                + " — Vergleichszeichen wird gedreht"
            : "Multipliziere beide Seiten mit " + ExpressionFormatter.format(factor);
        return new InequalityStep(
            "inequality_multiply_both_sides",
            next,
            description,
            assumptionsForMultiplier(factor, sign)
        );
    }

    /**
     * Divide both sides by {@code divisor}. Always surfaces a non-zero
     * assumption; flips the comparator if the divisor is strictly negative.
     */
    public InequalityStep divideBothSides(Inequality inequality, Expr divisor) {
        Objects.requireNonNull(inequality, "inequality");
        Objects.requireNonNull(divisor, "divisor");
        if (divisor instanceof NumberExpr number && number.value() == 0.0) {
            throw new IllegalArgumentException("Cannot divide an inequality by zero");
        }
        Sign sign = signOf(divisor);
        Comparator nextComparator = sign == Sign.NEGATIVE
            ? inequality.comparator().flip()
            : inequality.comparator();
        Inequality next = new Inequality(
            new BinaryExpr(inequality.left(), BinaryOperator.DIV, divisor),
            nextComparator,
            new BinaryExpr(inequality.right(), BinaryOperator.DIV, divisor)
        );
        String formatted = ExpressionFormatter.format(divisor);
        String description = sign == Sign.NEGATIVE
            ? "Dividiere beide Seiten durch " + formatted
                + " — Vergleichszeichen wird gedreht"
            : "Dividiere beide Seiten durch " + formatted;
        java.util.List<Assumption> assumptions = new java.util.ArrayList<>();
        assumptions.add(Assumption.nonZero(formatted));
        assumptions.addAll(assumptionsForMultiplier(divisor, sign));
        // Deduplicate: a strict positive/negative assumption already implies non-zero.
        return new InequalityStep(
            "inequality_divide_both_sides",
            next,
            description,
            java.util.List.copyOf(assumptions)
        );
    }

    private static List<Assumption> assumptionsForMultiplier(Expr factor, Sign sign) {
        if (factor instanceof NumberExpr) {
            // A literal sign is known statically — no symbolic assumption needed.
            return List.of();
        }
        String formatted = ExpressionFormatter.format(factor);
        return switch (sign) {
            case POSITIVE -> List.of(Assumption.positive(formatted));
            case NEGATIVE -> List.of(new Assumption(Assumption.Kind.CUSTOM, formatted + " < 0", List.of(formatted)));
            case UNKNOWN -> List.of(new Assumption(
                Assumption.Kind.CUSTOM,
                "sign(" + formatted + ") known",
                List.of(formatted)
            ));
        };
    }

    private static Sign signOf(Expr expr) {
        if (expr instanceof NumberExpr number) {
            if (number.value() > 0) {
                return Sign.POSITIVE;
            }
            if (number.value() < 0) {
                return Sign.NEGATIVE;
            }
            return Sign.UNKNOWN;
        }
        // Unary minus is parsed as `0 - x`; recognise that shape.
        if (expr instanceof BinaryExpr binary
            && binary.operator() == BinaryOperator.SUB
            && binary.left() instanceof NumberExpr zero
            && zero.value() == 0.0) {
            // Inner is treated as positive if it is a positive literal,
            // otherwise unknown.
            if (binary.right() instanceof NumberExpr inner && inner.value() > 0) {
                return Sign.NEGATIVE;
            }
        }
        return Sign.UNKNOWN;
    }

    private enum Sign {POSITIVE, NEGATIVE, UNKNOWN}
}
