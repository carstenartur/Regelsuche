package de.regelsuche.search.moves;

import de.regelsuche.assumption.AssumptionSignature;
import java.util.List;
import java.util.Set;

/** Source-only search position, independent of any learning implementation. */
public record MoveState(String expression, int searchDepth, int primitiveDepth, String previousRule,
                        List<String> assumptions, Set<String> capabilities, int complexityDebt) implements SearchExecution.Position<String>, de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(expression);v.reference(previousRule);v.reference(assumptions);v.reference(capabilities);}

    public MoveState {
        if (expression == null || expression.isBlank() || previousRule == null
                || searchDepth < 0 || primitiveDepth < 0 || complexityDebt < 0) {
            throw new IllegalArgumentException("invalid move state");
        }
        assumptions = AssumptionSignature.ofExpressions(assumptions).normalizedAssumptions();
        capabilities = Set.copyOf(capabilities);
    }
    public static MoveState root(String expression) { return new MoveState(expression, 0, 0, "", List.of(), Set.of(), 0); }
}
