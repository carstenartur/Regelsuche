package de.regelsuche.transform;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.HexFormat;

/** Exact recursive AST identity that does not erase associative grouping. */
final class ExprStructuralFingerprint {
    private ExprStructuralFingerprint() {
    }

    static String sha256(Expr expression) {
        StringBuilder descriptor = new StringBuilder();
        append(descriptor, expression);
        return sha256Text(descriptor.toString());
    }

    static String sha256Text(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    static int nodeCount(Expr expression) {
        int count = 0;
        ArrayDeque<Expr> pending = new ArrayDeque<>();
        pending.push(expression);
        while (!pending.isEmpty()) {
            Expr current = pending.pop();
            if (count == Integer.MAX_VALUE) {
                return count;
            }
            count++;
            if (current instanceof BinaryExpr binary) {
                pending.push(binary.right());
                pending.push(binary.left());
            } else if (current instanceof FunctionExpr function) {
                for (int index = function.arguments().size() - 1;
                        index >= 0;
                        index--) {
                    pending.push(function.arguments().get(index));
                }
            }
        }
        return count;
    }

    private static void append(StringBuilder target, Expr expression) {
        if (expression instanceof NumberExpr number) {
            token(target, "number");
            token(target, Long.toHexString(
                Double.doubleToLongBits(number.value())));
            return;
        }
        if (expression instanceof VariableExpr variable) {
            token(target, "variable");
            token(target, variable.name());
            return;
        }
        if (expression instanceof BinaryExpr binary) {
            token(target, "binary");
            token(target, binary.operator().name());
            append(target, binary.left());
            append(target, binary.right());
            return;
        }
        if (expression instanceof FunctionExpr function) {
            token(target, "function");
            token(target, function.name());
            token(target, Integer.toString(function.arguments().size()));
            function.arguments().forEach(argument -> append(target, argument));
            return;
        }
        throw new IllegalArgumentException(
            "unsupported expression type: "
                + expression.getClass().getName());
    }

    private static void token(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value);
    }
}
