package de.regelsuche.inventory;

import de.regelsuche.ast.*;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.MoveState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Source structure only; unknown degree is explicit. Variable spelling is absent from the context key. */
public record StructuralMoveContext(String rootOperator, int degree, int variables, int products, int powers,
        int repeatedSubtrees, List<String> assumptions, List<String> capabilities, int visitedNodes) {
    public static StructuralMoveContext of(MoveState state) {
        var root = new ExpressionParser().parse(new InputRequest(InputType.TERM, state.expression())).terms().getFirst();
        var seen = new HashMap<Expr, Integer>(); var variables = new HashSet<String>();
        int[] counts = new int[3]; collect(root, seen, variables, counts);
        String operator = root instanceof BinaryExpr binary ? binary.operator().name()
            : root instanceof FunctionExpr function ? "FUNCTION:" + function.name() : root instanceof VariableExpr ? "VARIABLE" : "NUMBER";
        return new StructuralMoveContext(operator, degree(root), variables.size(), counts[1], counts[2],
            seen.values().stream().mapToInt(count -> Math.max(0, count - 1)).sum(), state.assumptions(),
            state.capabilities().stream().sorted().toList(), counts[0]);
    }
    public String key() {
        return new JsonWriter().beginObject().property("root", rootOperator).property("degree", degree).property("variables", variables)
            .property("products", products).property("powers", powers).property("repeated", repeatedSubtrees)
            .stringArray("assumptions", assumptions).stringArray("capabilities", capabilities).endObject().toString();
    }
    private static void collect(Expr expr, HashMap<Expr, Integer> seen, HashSet<String> variables, int[] counts) {
        counts[0]++; seen.merge(expr, 1, Integer::sum);
        if (expr instanceof VariableExpr variable) variables.add(variable.name());
        else if (expr instanceof BinaryExpr binary) {
            if (binary.operator() == BinaryOperator.MUL) counts[1]++;
            if (binary.operator() == BinaryOperator.POW) counts[2]++;
            collect(binary.left(), seen, variables, counts); collect(binary.right(), seen, variables, counts);
        } else if (expr instanceof FunctionExpr function) for (var argument : function.arguments()) collect(argument, seen, variables, counts);
    }
    private static int degree(Expr expr) {
        if (expr instanceof VariableExpr) return 1;
        if (expr instanceof NumberExpr) return 0;
        if (!(expr instanceof BinaryExpr binary)) return -1;
        int left = degree(binary.left()), right = degree(binary.right());
        if (left < 0 || right < 0) return -1;
        return switch (binary.operator()) {
            case ADD, SUB -> Math.max(left, right);
            case MUL -> Math.min(1000, left + right);
            case POW -> binary.right() instanceof NumberExpr number && number.value().isInteger()
                && number.value().numerator().signum() >= 0 && number.value().numerator().bitLength() < 10
                ? Math.min(1000, left * number.value().numerator().intValueExact()) : -1;
            case DIV -> right == 0 ? left : -1;
        };
    }
}
