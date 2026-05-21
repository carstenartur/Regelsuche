package de.regelsuche.inequality;

/**
 * Strict / non-strict comparison operators used by {@link Inequality}.
 *
 * <p>Multiplying or dividing an inequality by a negative number flips a
 * comparator to its mirror: {@code <} ↔ {@code >}, {@code <=} ↔ {@code >=}.
 * {@link #flip()} models exactly that operation.</p>
 */
public enum Comparator {
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    private final String symbol;

    Comparator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public Comparator flip() {
        return switch (this) {
            case LT -> GT;
            case LE -> GE;
            case GT -> LT;
            case GE -> LE;
        };
    }
}
