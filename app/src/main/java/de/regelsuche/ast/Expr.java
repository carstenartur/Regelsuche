package de.regelsuche.ast;

public sealed interface Expr permits BinaryExpr, NumberExpr, VariableExpr {
}
