package de.regelsuche.egraph;

/**
 * Index key for ENode candidate lookup.
 */
public record ENodeSignature(String symbol, int arity) {

    public ENodeSignature {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
        if (arity < 0) {
            throw new IllegalArgumentException("arity must be >= 0");
        }
    }

    public static ENodeSignature of(ENode node) {
        return new ENodeSignature(node.symbol(), node.children().size());
    }
}
