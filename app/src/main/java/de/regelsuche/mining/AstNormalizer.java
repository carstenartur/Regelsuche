package de.regelsuche.mining;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AstNormalizer {
    private final ExpressionParser parser = new ExpressionParser();

    public NormalizedNode normalize(String expression) {
        Expr parsed = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        return normalize(parsed, new HashMap<>());
    }

    private NormalizedNode normalize(Expr expression, Map<String, String> variables) {
        if (expression instanceof NumberExpr numberExpr) {
            if (Math.rint(numberExpr.value()) != numberExpr.value()) {
                throw new IllegalArgumentException("Only integer literals can be generalized: " + numberExpr.value());
            }
            return NormalizedNode.number((int) numberExpr.value());
        }
        if (expression instanceof VariableExpr variableExpr) {
            String canonicalName = variables.computeIfAbsent(variableExpr.name(), key -> variables.isEmpty() ? "x" : "v" + variables.size());
            return NormalizedNode.variable(canonicalName);
        }
        BinaryExpr binaryExpr = (BinaryExpr) expression;
        NormalizedNode left = normalize(binaryExpr.left(), variables);
        NormalizedNode right = normalize(binaryExpr.right(), variables);
        return switch (binaryExpr.operator()) {
            case ADD -> NormalizedNode.add(List.of(left, right));
            case SUB -> NormalizedNode.add(List.of(left, NormalizedNode.multiply(List.of(NormalizedNode.number(-1), right))));
            case MUL -> NormalizedNode.multiply(List.of(left, right));
            case DIV -> NormalizedNode.multiply(List.of(left, NormalizedNode.pow(right, NormalizedNode.number(-1))));
            case POW -> NormalizedNode.pow(left, right);
        };
    }
}
