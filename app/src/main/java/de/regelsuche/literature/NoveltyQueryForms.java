package de.regelsuche.literature;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Offline retrieval strings for bounded scalar equations, not equivalence certificates. */
public final class NoveltyQueryForms {
    private NoveltyQueryForms() { }
    public static Map<String, String> forEquation(String statement) {
        if (statement == null || statement.isBlank() || statement.length() > 128
                || statement.indexOf('=') < 1 || statement.indexOf('=') != statement.lastIndexOf('=')) {
            throw new IllegalArgumentException("expected a bounded scalar equation");
        }
        var eq = new ExpressionParser().parseEquation(statement);
        var names = new LinkedHashMap<String,String>();
        var left = render(eq.left(), names, false);
        var right = render(eq.right(), names, false);
        int eqAt = statement.indexOf('=');
        var forms = new LinkedHashMap<String,String>();
        forms.put("EXACT", statement);
        forms.put("ALPHA", "(" + left + "=" + right + ")");
        forms.put("STRUCTURAL", "eq(" + render(eq.left(), names, true) + "," + render(eq.right(), names, true) + ")");
        forms.put("EQUIVALENT", statement.substring(eqAt+1).strip() + " = " + statement.substring(0,eqAt).strip());
        return Collections.unmodifiableMap(forms);
    }
    private static String render(Expr expression, Map<String,String> names, boolean shape) {
        if (expression instanceof VariableExpr v) return names.computeIfAbsent(v.name(), k -> "v" + names.size());
        if (expression instanceof NumberExpr n) return shape ? "number" : n.value().toString();
        if (expression instanceof FunctionExpr f) {
            return f.name() + f.arguments().stream().map(a -> render(a,names,shape)).collect(Collectors.joining(",", "(", ")"));
        }
        if (expression instanceof BinaryExpr b) {
            String left = render(b.left(), names, shape);
            String right = render(b.right(), names, shape);
            return shape ? b.operator().name()+"("+left+","+right+")" : "("+left+b.operator().symbol()+right+")";
        }
        throw new IllegalArgumentException("unsupported query expression");
    }
}
