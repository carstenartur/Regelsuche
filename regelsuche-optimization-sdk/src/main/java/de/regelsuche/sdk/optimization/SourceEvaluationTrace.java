package de.regelsuche.sdk.optimization;

import de.regelsuche.ast.*;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.PreparedJointComputation;
import java.util.*;

/** Ordered operation occurrences, including duplicates and eliminated/dead computations. */
public record SourceEvaluationTrace(List<Occurrence> occurrences) {
    public record Occurrence(String sourceId, Expr expression, NumericKind declaredKind, NumericKind evaluatedKind) {
        public Occurrence {
            if (sourceId == null || sourceId.isBlank()) throw new IllegalArgumentException("SOURCE_ID_REQUIRED");
            Objects.requireNonNull(expression); Objects.requireNonNull(declaredKind); Objects.requireNonNull(evaluatedKind);
            if (JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr)
                throw new IllegalArgumentException("OPERATION_OCCURRENCE_REQUIRED");
        }
    }
    public SourceEvaluationTrace { occurrences = List.copyOf(occurrences); }
    /** Actual replacement schedule: one occurrence per prepared DAG operation.
     * This does not reconstruct or replace the adapter's original source occurrences.
     */
    public static SourceEvaluationTrace fromPrepared(PreparedJointComputation prepared) {
        if(prepared.nodes().size()>JointComputationPlan.MAX_NODES) throw new IllegalArgumentException("TRACE_STRUCTURAL_BOUND");
        var result=new ArrayList<Occurrence>();
        for(var node:prepared.nodes()) if(node.operation()!=null && !JavaExpressions.isLiteral(node.expression())) {
            var kind=NumericKind.fromType(node.type());
            result.add(new Occurrence("prepared-operation-"+result.size(),node.expression(),kind,kind));
        }
        return new SourceEvaluationTrace(result);
    }
    /** For synthetic expression plans only. Source adapters must supply the actual statement trace. */
    public static SourceEvaluationTrace fromPlan(JointComputationPlan plan) {
        var result = new ArrayList<Occurrence>();
        for (var expression : plan.outputExpressions()) append(expression, result, 0);
        return new SourceEvaluationTrace(result);
    }
    static void append(Expr expression, List<Occurrence> target, int depth) {
        if (depth > JointComputationPlan.MAX_DEPTH || target.size() >= JointComputationPlan.MAX_NODES)
            throw new IllegalArgumentException("TRACE_STRUCTURAL_BOUND");
        if (JavaExpressions.isLiteral(expression) || expression instanceof VariableExpr) return;
        for (var child : JavaExpressions.operands(expression)) append(child, target, depth + 1);
        var kind = JavaExpressions.resultKind(expression);
        target.add(new Occurrence("operation-" + target.size(), expression, kind, kind));
    }
}
