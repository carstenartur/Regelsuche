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
        /** Variable is known to be real-valued. */
        REAL,
        /** Variable is known to be integer-valued. */
        INTEGER,
        /** Variable is known to be rational-valued. */
        RATIONAL,
        /** Argument must be strictly positive. */
        POSITIVE,
        /** Argument must be non-negative. */
        NON_NEGATIVE,
        /** Explicitly unknown / inconclusive domain information. */
        UNKNOWN,
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

    public static Assumption nonzero(String denominator) {
        return nonZero(denominator);
    }

    /** {@code argument > 0}, useful for logarithm/root domains. */
    public static Assumption positive(String argument) {
        return new Assumption(Kind.POSITIVE, argument + " > 0", List.of(argument));
    }

    /** {@code argument >= 0}, useful for square-root identities. */
    public static Assumption nonNegative(String argument) {
        return new Assumption(Kind.NON_NEGATIVE, argument + " >= 0", List.of(argument));
    }

    public static Assumption real(String symbol) {
        return new Assumption(Kind.REAL, symbol + " ∈ R", List.of(symbol));
    }

    public static Assumption integer(String symbol) {
        return new Assumption(Kind.INTEGER, symbol + " ∈ Z", List.of(symbol));
    }

    public static Assumption rational(String symbol) {
        return new Assumption(Kind.RATIONAL, symbol + " ∈ Q", List.of(symbol));
    }

    public static Assumption unknown(String symbol) {
        return new Assumption(Kind.UNKNOWN, symbol + " ? unknown", List.of(symbol));
    }

    public AssumptionTruthValue truthValueUnder(Iterable<Assumption> knownAssumptions) {
        if (kind == Kind.UNKNOWN) {
            return AssumptionTruthValue.UNKNOWN;
        }
        if (knownAssumptions == null) {
            return AssumptionTruthValue.UNKNOWN;
        }
        for (Assumption known : knownAssumptions) {
            if (known == null || !sameSymbols(known)) {
                continue;
            }
            if (known.expression().equals(expression())) {
                return AssumptionTruthValue.TRUE;
            }
            if (implies(known.kind(), kind)) {
                return AssumptionTruthValue.TRUE;
            }
            if (contradicts(known.kind(), kind)) {
                return AssumptionTruthValue.FALSE;
            }
        }
        return AssumptionTruthValue.UNKNOWN;
    }

    private boolean sameSymbols(Assumption other) {
        return symbols.equals(other.symbols);
    }

    private static boolean implies(Kind known, Kind required) {
        if (known == required) {
            return true;
        }
        return switch (required) {
            case NON_ZERO -> known == Kind.POSITIVE;
            case RATIONAL -> known == Kind.INTEGER;
            case REAL -> known == Kind.INTEGER || known == Kind.RATIONAL || known == Kind.POSITIVE || known == Kind.NON_NEGATIVE;
            default -> false;
        };
    }

    private static boolean contradicts(Kind known, Kind required) {
        return false;
    }

    @Override
    public String toString() {
        return kind + "(" + expression + ")";
    }
}
