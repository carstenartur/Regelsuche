package de.regelsuche.ast;

public record VariableExpr(String name) implements Expr {
    public VariableExpr {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
