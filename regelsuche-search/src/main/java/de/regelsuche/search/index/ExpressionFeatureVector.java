package de.regelsuche.search.index;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compact structural fingerprint used as a cheap mismatch prefilter. */
public record ExpressionFeatureVector(
    Map<String, Integer> operatorCounts,
    int treeDepth,
    int variableCount,
    boolean commutativeOperatorPresent,
    int polynomialDegree,
    Set<String> functionSymbols,
    boolean matrixLike,
    boolean operatorLike
) {
    public ExpressionFeatureVector {
        operatorCounts = operatorCounts == null ? Map.of() : Map.copyOf(operatorCounts);
        functionSymbols = functionSymbols == null ? Set.of() : Set.copyOf(functionSymbols);
    }

    public static ExpressionFeatureVector of(Expr expr) {
        MutableFeatures mutable = new MutableFeatures();
        collect(expr, 1, mutable);
        return mutable.toVector();
    }

    public static ExpressionFeatureVector parse(String expression) {
        try {
            return of(new ExpressionParser().parseTerm(expression == null ? "" : expression));
        } catch (RuntimeException ignored) {
            return heuristic(expression);
        }
    }

    public boolean canMatch(ExpressionFeatureVector query) {
        if (query == null) {
            return true;
        }
        if (!query.functionSymbols.containsAll(functionSymbols)) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : requiredOperators().entrySet()) {
            if (query.operatorCounts.getOrDefault(entry.getKey(), 0) < entry.getValue()) {
                return false;
            }
        }
        return polynomialDegree <= 0 || query.polynomialDegree <= 0 || polynomialDegree <= query.polynomialDegree;
    }

    private Map<String, Integer> requiredOperators() {
        Map<String, Integer> required = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : operatorCounts.entrySet()) {
            if ("+".equals(entry.getKey()) || "*".equals(entry.getKey()) || "^".equals(entry.getKey())) {
                required.put(entry.getKey(), entry.getValue());
            }
        }
        return required;
    }

    private static void collect(Expr expr, int depth, MutableFeatures features) {
        features.treeDepth = Math.max(features.treeDepth, depth);
        if (expr instanceof NumberExpr) {
            return;
        }
        if (expr instanceof VariableExpr variable) {
            features.variables.add(variable.name());
            if (variable.name().length() == 1 && Character.isUpperCase(variable.name().charAt(0))) {
                features.matrixLike = true;
            }
            return;
        }
        if (expr instanceof FunctionExpr function) {
            String name = function.name().toLowerCase(Locale.ROOT);
            features.functions.add(name);
            features.operatorLike |= name.contains("matrix") || name.contains("det") || name.contains("transpose");
            for (Expr argument : function.arguments()) {
                collect(argument, depth + 1, features);
            }
            return;
        }
        BinaryExpr binary = (BinaryExpr) expr;
        String symbol = binary.operator().symbol();
        features.operators.merge(symbol, 1, Integer::sum);
        features.commutative |= binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.MUL;
        collect(binary.left(), depth + 1, features);
        collect(binary.right(), depth + 1, features);
    }

    private static ExpressionFeatureVector heuristic(String expression) {
        String value = expression == null ? "" : expression;
        MutableFeatures mutable = new MutableFeatures();
        int depth = 1;
        int maxDepth = 1;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ("+-*/^".indexOf(ch) >= 0) {
                mutable.operators.merge(String.valueOf(ch), 1, Integer::sum);
                mutable.commutative |= ch == '+' || ch == '*';
            } else if (ch == '(') {
                maxDepth = Math.max(maxDepth, ++depth);
            } else if (ch == ')') {
                depth = Math.max(1, depth - 1);
            }
        }
        java.util.regex.Matcher functions = java.util.regex.Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)\\s*\\(").matcher(value);
        while (functions.find()) {
            mutable.functions.add(functions.group(1).toLowerCase(Locale.ROOT));
        }
        java.util.regex.Matcher variables = java.util.regex.Pattern.compile("\\b[A-Za-z][A-Za-z0-9_]*\\b").matcher(value);
        while (variables.find()) {
            String variable = variables.group();
            if (!mutable.functions.contains(variable.toLowerCase(Locale.ROOT))) {
                mutable.variables.add(variable);
            }
        }
        mutable.treeDepth = maxDepth;
        return mutable.toVector();
    }

    private static int degree(Expr expr) {
        if (expr instanceof NumberExpr) return 0;
        if (expr instanceof VariableExpr) return 1;
        if (expr instanceof FunctionExpr) return 0;
        BinaryExpr binary = (BinaryExpr) expr;
        return switch (binary.operator()) {
            case ADD, SUB -> Math.max(degree(binary.left()), degree(binary.right()));
            case MUL -> degree(binary.left()) + degree(binary.right());
            case DIV -> degree(binary.left());
            case POW -> degree(binary.left()) * integerExponent(binary.right());
        };
    }

    private static int integerExponent(Expr expr) {
        if (expr instanceof NumberExpr number && number.value() >= 0 && Math.rint(number.value()) == number.value()) {
            return (int) number.value();
        }
        return 1;
    }

    private static final class MutableFeatures {
        private final Map<String, Integer> operators = new LinkedHashMap<>();
        private final Set<String> variables = new LinkedHashSet<>();
        private final Set<String> functions = new LinkedHashSet<>();
        private int treeDepth = 0;
        private boolean commutative;
        private boolean matrixLike;
        private boolean operatorLike;

        private ExpressionFeatureVector toVector() {
            return new ExpressionFeatureVector(
                operators,
                treeDepth,
                variables.size(),
                commutative,
                estimateDegree(),
                functions,
                matrixLike,
                operatorLike
            );
        }

        private int estimateDegree() {
            int powers = operators.getOrDefault("^", 0);
            int multiplications = operators.getOrDefault("*", 0);
            return Math.max(variables.isEmpty() ? 0 : 1, powers > 0 ? 2 : 1) + Math.max(0, multiplications);
        }
    }
}
