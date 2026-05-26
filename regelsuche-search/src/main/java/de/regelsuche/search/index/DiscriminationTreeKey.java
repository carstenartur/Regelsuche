package de.regelsuche.search.index;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

record DiscriminationTreeKey(Set<String> requiredPaths) {
    static DiscriminationTreeKey of(Expr expr) {
        Set<String> paths = new LinkedHashSet<>();
        collect(expr, "$", paths);
        return new DiscriminationTreeKey(paths);
    }

    static DiscriminationTreeKey parse(String expression) {
        try {
            return of(new ExpressionParser().parseTerm(expression == null ? "" : expression));
        } catch (RuntimeException ignored) {
            return new DiscriminationTreeKey(Set.of("$/" + RootSymbolTermRuleIndex.rootSymbol(expression)));
        }
    }

    boolean compatibleWith(DiscriminationTreeKey query) {
        return query == null || query.requiredPaths.containsAll(requiredPaths);
    }

    private static void collect(Expr expr, String path, Set<String> paths) {
        if (expr instanceof VariableExpr variable && isPlaceholder(variable.name())) {
            return;
        }
        if (expr instanceof NumberExpr) {
            paths.add(path + "/num:" + formatNumber(((NumberExpr) expr).value()));
            return;
        }
        if (expr instanceof VariableExpr) {
            return;
        }
        if (expr instanceof FunctionExpr function) {
            String current = path + "/fn:" + function.name().toLowerCase(java.util.Locale.ROOT);
            paths.add(current);
            for (int i = 0; i < function.arguments().size(); i++) {
                collect(function.arguments().get(i), current + "/" + i, paths);
            }
            return;
        }
        BinaryExpr binary = (BinaryExpr) expr;
        String current = path + "/op:" + binary.operator().symbol();
        paths.add(current);
        if (binary.operator() == BinaryOperator.ADD || binary.operator() == BinaryOperator.MUL) {
            List<Expr> children = new ArrayList<>(List.of(binary.left(), binary.right()));
            Collections.sort(children, java.util.Comparator.comparing(Object::toString));
            for (int i = 0; i < children.size(); i++) {
                collect(children.get(i), current + "/" + i, paths);
            }
        } else {
            collect(binary.left(), current + "/0", paths);
            collect(binary.right(), current + "/1", paths);
        }
    }

    private static boolean isPlaceholder(String name) {
        return name.length() == 1 && Character.isUpperCase(name.charAt(0));
    }

    private static String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
