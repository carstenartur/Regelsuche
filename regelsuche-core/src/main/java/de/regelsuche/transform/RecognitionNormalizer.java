package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded canonicalization used only for recognition and anti-unification. */
public final class RecognitionNormalizer {
    private RecognitionNormalizer() {
    }

    public static Expr normalize(Expr expression, RecognitionProfile profile) {
        if (expression instanceof FunctionExpr function) {
            return new FunctionExpr(function.name(), function.arguments().stream()
                .map(argument -> normalize(argument, profile)).toList());
        }
        if (!(expression instanceof BinaryExpr binary)) {
            return expression;
        }
        Expr left = normalize(binary.left(), profile);
        Expr right = normalize(binary.right(), profile);
        BinaryOperator operator = binary.operator();

        if (profile.inferAlgebraicBindings() && operator == BinaryOperator.MUL && left.equals(right)) {
            return new BinaryExpr(left, BinaryOperator.POW, new NumberExpr(2));
        }
        if (profile.isAssociative(operator)) {
            List<Expr> operands = new ArrayList<>();
            flatten(left, operator, operands);
            flatten(right, operator, operands);
            if (profile.isCommutative(operator)) {
                operands.sort(Comparator.comparing(Object::toString));
            }
            return rebuild(operands, operator);
        }
        return new BinaryExpr(left, operator, right);
    }

    private static void flatten(Expr expression, BinaryOperator operator, List<Expr> target) {
        if (expression instanceof BinaryExpr binary && binary.operator() == operator) {
            flatten(binary.left(), operator, target);
            flatten(binary.right(), operator, target);
        } else {
            target.add(expression);
        }
    }

    private static Expr rebuild(List<Expr> operands, BinaryOperator operator) {
        if (operands.isEmpty()) {
            throw new IllegalArgumentException("cannot rebuild an empty operand list");
        }
        Expr result = operands.get(0);
        for (int i = 1; i < operands.size(); i++) {
            result = new BinaryExpr(result, operator, operands.get(i));
        }
        return result;
    }
}
