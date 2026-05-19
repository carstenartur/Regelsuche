package de.regelsuche.ast;

public record Equation(Expr left, Expr right) {
    public Equation {
        if (left == null || right == null) {
            throw new IllegalArgumentException("left and right must not be null");
        }
    }
}
