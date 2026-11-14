package de.regelsuche.literature;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.canonical.PolynomialNormalizer;
import java.util.List;
import java.util.TreeSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Offline retrieval strings for bounded scalar equations, not equivalence certificates. */
public final class NoveltyQueryForms {
    private NoveltyQueryForms() { }
    public static Map<String, String> forEquation(String statement) {
        return forEquation(statement, List.of());
    }

    public static Map<String, String> forEquation(String statement, List<String> assumptions) {
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
        if (!assumptions.isEmpty()) {
            String restricted = statement + " assuming " + String.join(", ", assumptions);
            if (restricted.length() > 1000) throw new IllegalArgumentException("assumptions exceed query scope");
            forms.put("ASSUMPTIONS", restricted);
        }
        var terms = new TreeSet<String>();
        terms.add("scalar equation");
        classify(eq.left(), terms);
        classify(eq.right(), terms);
        forms.put("CLASSIFICATION", String.join(" ", terms));
        var normalizer = new PolynomialNormalizer();
        var normalizedLeft = normalizer.normalize(eq.left());
        var normalizedRight = normalizer.normalize(eq.right());
        if (normalizedLeft.isPresent() && normalizedRight.isPresent()) {
            String normal = ExpressionFormatter.format(normalizedLeft.get()) + " = "
                + ExpressionFormatter.format(normalizedRight.get());
            if (normal.length() <= 1000) forms.put("POLYNOMIAL_NORMAL_FORM", normal);
        }
        return Collections.unmodifiableMap(forms);
    }

    private static void classify(Expr expression, java.util.Set<String> terms) {
        if (expression instanceof BinaryExpr binary) {
            terms.add(binary.operator().name().toLowerCase(java.util.Locale.ROOT));
            classify(binary.left(), terms);
            classify(binary.right(), terms);
        } else if (expression instanceof FunctionExpr function) {
            terms.add(function.name());
            function.arguments().forEach(argument -> classify(argument, terms));
        }
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
