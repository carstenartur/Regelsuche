package de.regelsuche.ast;

public record BinaryExpr(Expr left, BinaryOperator operator, Expr right) implements Expr {
    public BinaryExpr {
        if (left == null || operator == null || right == null) {
            throw new IllegalArgumentException("left, operator and right must not be null");
        }
    }
    @Override public boolean equals(Object other) {
        if (de.regelsuche.retention.RetainedOperation.isObserved()) return ExpressionIdentity.same(this, other);
        return this == other || other instanceof BinaryExpr binary && left.equals(binary.left)
            && operator == binary.operator && right.equals(binary.right);
    }
    @Override public int hashCode() {
        if (de.regelsuche.retention.RetainedOperation.isObserved()) return ExpressionIdentity.valueHash(this);
        return 31 * (31 * left.hashCode() + operator.hashCode()) + right.hashCode();
    }

}
