package de.regelsuche.ast;

public record BinaryExpr(Expr left, BinaryOperator operator, Expr right) implements Expr {
    public BinaryExpr {
        if (left == null || operator == null || right == null) {
            throw new IllegalArgumentException("left, operator and right must not be null");
        }
    }
}
