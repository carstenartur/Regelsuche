package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.search.program.JointComputationPlan.Output;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The source trace records evaluations, not repeated expansion of local values. */
class SourceTraceSharingTest {
    private final ComputationOptimizer optimizer = new ComputationOptimizer();
    private final Expr input = new VariableExpr("x");
    private final Expr sum = JavaExpressions.operation(NumericKind.INT,
        NumericOperation.ADD, input, JavaExpressions.literal(1));

    @Test
    void intermediateRetainedAsAnOutputIsNotEvaluatedAgainByItsConsumer() {
        // Java: int sum = x + 1; int result = sum + 0;
        Expr result = JavaExpressions.operation(NumericKind.INT,
            NumericOperation.ADD, sum, JavaExpressions.literal(0));
        var plan = plan(List.of(new Output("sum", NumericKind.INT.type(), sum),
            new Output("result", NumericKind.INT.type(), result)));
        var trace = trace(sum, result);

        var verified = assertInstanceOf(VerificationResult.Verified.class,
            optimizer.verify(request(plan, trace), plan, CancellationToken.NONE));

        assertEquals(trace, verified.obligations().originalTrace());
        assertEquals(2, verified.obligations().originalTrace().occurrences().size());
        assertEquals(Map.of("sum", Integer.MIN_VALUE, "result", Integer.MIN_VALUE),
            ComputationOptimizer.prepare(plan).execute(Map.of("x", Integer.MAX_VALUE)));
    }

    @Test
    void onePreviouslyEvaluatedValueMaySupplyBothOperandsOfOneOutput() {
        // Java: int sum = x + 1; int result = sum + sum;
        Expr result = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, sum, sum);
        var plan = plan(List.of(new Output("result", NumericKind.INT.type(), result)));
        var trace = trace(sum, result);

        var verified = assertInstanceOf(VerificationResult.Verified.class,
            optimizer.verify(request(plan, trace), plan, CancellationToken.NONE));

        assertEquals(trace, verified.obligations().originalTrace());
        assertEquals(2, verified.obligations().originalTrace().occurrences().size());
        assertEquals(Map.of("result", 6), ComputationOptimizer.prepare(plan).execute(Map.of("x", 2)));
    }

    @Test
    void aRequiredOperationCannotDisappearFromTheSourceTrace() {
        Expr result = JavaExpressions.operation(NumericKind.INT,
            NumericOperation.ADD, sum, JavaExpressions.literal(0));
        var plan = plan(List.of(new Output("result", NumericKind.INT.type(), result)));
        var missingSum = trace(result);

        assertInstanceOf(VerificationResult.Unsupported.class,
            optimizer.verify(request(plan, missingSum), plan, CancellationToken.NONE));
    }

    private static JointComputationPlan plan(List<Output> outputs) {
        return new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(), outputs);
    }

    private static SourceEvaluationTrace trace(Expr... expressions) {
        var occurrences = new java.util.ArrayList<SourceEvaluationTrace.Occurrence>();
        for (int index = 0; index < expressions.length; index++) {
            occurrences.add(new SourceEvaluationTrace.Occurrence("statement-" + index,
                expressions[index], NumericKind.INT, NumericKind.INT));
        }
        return new SourceEvaluationTrace(occurrences);
    }

    private static OptimizationRequest request(JointComputationPlan plan, SourceEvaluationTrace trace) {
        return new OptimizationRequest(plan, trace, Set.of(NumericKind.INT),
            ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
            OptimizationGoal.READABILITY, OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
    }
}
