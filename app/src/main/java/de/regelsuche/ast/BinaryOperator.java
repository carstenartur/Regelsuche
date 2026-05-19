package de.regelsuche.ast;

public enum BinaryOperator {
    ADD("+", 1),
    SUB("-", 1),
    MUL("*", 2),
    DIV("/", 2),
    POW("^", 3);

    private final String symbol;
    private final int precedence;

    BinaryOperator(String symbol, int precedence) {
        this.symbol = symbol;
        this.precedence = precedence;
    }

    public String symbol() {
        return symbol;
    }

    public int precedence() {
        return precedence;
    }
}
