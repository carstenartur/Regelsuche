package de.regelsuche.value;

/** Algebraic laws explicitly attached to a mathematical value operator. */
public record OperatorLaws(boolean associative, boolean commutative, boolean idempotent) {
    public static final OperatorLaws NONE = new OperatorLaws(false, false, false);
    public static final OperatorLaws ASSOCIATIVE_COMMUTATIVE = new OperatorLaws(true, true, false);

    public boolean supportsUnorderedNaryValue() {
        return associative && commutative;
    }
}
