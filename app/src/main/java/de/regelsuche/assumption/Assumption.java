package de.regelsuche.assumption;

import java.util.List;
import java.util.Objects;

/**
 * Symbolic side condition attached to a rule, candidate or transformation
 * step.
 *
 * <p>Assumptions are kept as a small first-class model so that downstream
 * components (proof bridges, validation, inventory exports) can reason about
 * the conditions a rule depends on. They are intentionally lightweight: the
 * {@link Kind} enum covers the most common cases (non-zero divisor, positive
 * argument for a logarithm, domain of a function, custom predicate); a
 * free-form {@link #expression()} carries the actual symbolic statement
 * (e.g. {@code "b != 0"} or {@code "x > 0"}).</p>
 */
public record Assumption(Kind kind, String expression, List<String> symbols) {
    public Assumption {
        Objects.requireNonNull(kind, "kind");
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression must not be blank");
        }
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    public Assumption(Kind kind, String expression) {
        this(kind, expression, List.of());
    }

    /** Most common assumption kinds. */
    public enum Kind {
        /** Denominator (or other given subterm) must be non-zero. */
        NON_ZERO,
        /** Argument must be strictly positive. */
        POSITIVE,
        /** Argument must be non-negative. */
        NON_NEGATIVE,
        /** Variable belongs to a particular domain (e.g. integer). */
        DOMAIN,
        /** Free-form predicate. */
        CUSTOM
    }

    /**
     * Common factory: a divisor that must be non-zero. The {@code denominator}
     * is rendered verbatim into the assumption expression.
     */
    public static Assumption nonZero(String denominator) {
        return new Assumption(Kind.NON_ZERO, denominator + " != 0", List.of(denominator));
    }

    /** {@code argument > 0}, useful for logarithm/root domains. */
    public static Assumption positive(String argument) {
        return new Assumption(Kind.POSITIVE, argument + " > 0", List.of(argument));
    }

    /** {@code argument >= 0}, useful for square-root identities. */
    public static Assumption nonNegative(String argument) {
        return new Assumption(Kind.NON_NEGATIVE, argument + " >= 0", List.of(argument));
    }

    @Override
    public String toString() {
        return kind + "(" + expression + ")";
    }
}
