package de.regelsuche.api.searchgraph;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups expressions whose canonical AST skeleton is identical.
 *
 * <p>A "skeleton" replaces every concrete leaf (numbers, variables) with a
 * placeholder and folds n-ary structures so that, e.g.
 * {@code (x+1)*(x+1)} and {@code (y+2)*(y+2)} share the skeleton
 * {@code (V+N)*(V+N)} and end up in the same {@link
 * ClusterType#STRUCTURAL_PATTERN} cluster.</p>
 *
 * <p>Cohesion is {@code count / total} for the node set surfaced.</p>
 */
public final class StructuralExpressionClusterer {

    private final ExpressionParser parser = new ExpressionParser();
    private final int minSize;

    public StructuralExpressionClusterer() {
        this(2);
    }

    public StructuralExpressionClusterer(int minSize) {
        this.minSize = Math.max(2, minSize);
    }

    public List<SearchGraphClusterDto> cluster(Collection<String> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> bySkeleton = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String expr : expressions) {
            if (!seen.add(expr)) {
                continue;
            }
            String skeleton = skeleton(expr);
            if (skeleton.isEmpty()) {
                continue;
            }
            bySkeleton.computeIfAbsent(skeleton, k -> new ArrayList<>()).add(expr);
        }
        List<SearchGraphClusterDto> clusters = new ArrayList<>();
        int counter = 0;
        int total = (int) expressions.stream().distinct().count();
        for (Map.Entry<String, List<String>> entry : bySkeleton.entrySet()) {
            if (entry.getValue().size() < minSize) {
                continue;
            }
            double cohesion = total == 0 ? 0.0 : (double) entry.getValue().size() / total;
            clusters.add(new SearchGraphClusterDto(
                "struct-" + (counter++),
                "skeleton:" + entry.getKey(),
                ClusterType.STRUCTURAL_PATTERN,
                entry.getValue(),
                List.of(),
                Math.min(1.0, cohesion)
            ));
        }
        return clusters;
    }

    String skeleton(String expression) {
        try {
            Expr expr = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
            return render(expr);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String render(Expr expr) {
        if (expr instanceof NumberExpr) {
            return "N";
        }
        if (expr instanceof VariableExpr) {
            return "V";
        }
        if (expr instanceof BinaryExpr binary) {
            return "(" + render(binary.left()) + binary.operator().symbol() + render(binary.right()) + ")";
        }
        if (expr instanceof FunctionExpr fn) {
            StringBuilder sb = new StringBuilder(fn.name()).append('(');
            for (int i = 0; i < fn.arguments().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(render(fn.arguments().get(i)));
            }
            return sb.append(')').toString();
        }
        return "?";
    }
}
