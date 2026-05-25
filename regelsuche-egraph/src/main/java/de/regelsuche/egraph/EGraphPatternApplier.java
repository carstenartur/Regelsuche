package de.regelsuche.egraph;

import de.regelsuche.transform.PatternExpr;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Egg-style "Applier": given a {@link PatternExpr} target and a set of
 * placeholder bindings (placeholder name → {@link EClassId}), construct
 * the corresponding e-node(s) inside an {@link EGraph} and return the
 * {@link EClassId} of the rewritten right-hand side.
 *
 * <p>The caller then typically {@code union}s this id with the id of the
 * matched left-hand side and {@code rebuild}s the graph to propagate the
 * new equivalence through congruence closure.</p>
 */
public final class EGraphPatternApplier {

    private final EGraph eGraph;

    public EGraphPatternApplier(EGraph eGraph) {
        this.eGraph = eGraph;
    }

    /**
     * Build the pattern target inside the e-graph using the given
     * bindings, and return the id of the resulting e-class.
     */
    public EClassId instantiate(PatternExpr target, Map<String, EClassId> bindings) {
        if (target instanceof PatternExpr.Placeholder placeholder) {
            EClassId id = bindings.get(placeholder.name());
            if (id == null) {
                throw new IllegalArgumentException("Missing binding for " + placeholder.name());
            }
            return eGraph.find(id);
        }
        if (target instanceof PatternExpr.LiteralNumber literal) {
            return eGraph.add(ENode.leaf("num:" + formatNumber(literal.value())));
        }
        if (target instanceof PatternExpr.Operation operation) {
            EClassId left = instantiate(operation.left(), bindings);
            EClassId right = instantiate(operation.right(), bindings);
            return eGraph.add(new ENode("op:" + operation.operator().name(), List.of(left, right)));
        }
        if (target instanceof PatternExpr.Function function) {
            List<EClassId> arguments = new ArrayList<>(function.arguments().size());
            for (PatternExpr argument : function.arguments()) {
                arguments.add(instantiate(argument, bindings));
            }
            return eGraph.add(new ENode("fn:" + function.name(), arguments));
        }
        throw new IllegalArgumentException("Unsupported pattern: " + target.getClass());
    }

    private static String formatNumber(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }
}
