package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExecutionCostRankingTest {
    @Test void multiplicationAndGrowingIntermediatesDependOnOperandMagnitudes() {
        var x = new VariableExpr("x");
        var square = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MULTIPLY, x, x);
        var fourth = JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MULTIPLY, square, square);
        var small = request(NumericKind.BIG_INTEGER, square, 32);
        var large = request(NumericKind.BIG_INTEGER, square, 2048);
        var growth = request(NumericKind.BIG_INTEGER, fourth, 2048);
        var a = estimate(small); var b = estimate(large); var c = estimate(growth);
        assertTrue(b.operationWork() > a.operationWork() * 100);
        assertTrue(c.operationWork() > b.operationWork() * 2);
        assertTrue(c.peakWords() > b.peakWords());
        assertTrue(c.inspectionWork() > 0);
    }

    @Test void unknownBoundsRemainExplicitAndPrimitiveCostsAreUnchanged() {
        Expr x = new VariableExpr("x");
        var unknown = request(NumericKind.BIG_INTEGER,
                JavaExpressions.operation(NumericKind.BIG_INTEGER, NumericOperation.MULTIPLY, x, x), -1);
        assertTrue(NumericExecutionCost.estimate(unknown, ComputationOptimizer.prepare(unknown.plan())).isEmpty());
        var primitive = request(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, x, x), -1);
        assertEquals(ComputationOptimizer.prepare(primitive.plan()).cost().operationWork(), estimate(primitive).operationWork());
    }

    @Test void consumerCostsParticipateInSelectionWithoutChangingTheProofContract() {
        Expr x = new VariableExpr("x");
        var source = JavaExpressions.operation(NumericKind.INT, NumericOperation.SUBTRACT, x, x);
        var request = request(NumericKind.INT, source, -1);
        assertInstanceOf(OptimizationResult.Candidate.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE));
        var result = new ComputationOptimizer().optimize(request, CancellationToken.NONE,
                (plan, prepared) -> new CandidateRanking.Assessment(plan.outputExpressions().equals(List.of(source)) ? 0 : 100, 1));
        assertInstanceOf(OptimizationResult.NoImprovement.class, result);
        var preferred = new ComputationOptimizer().optimize(request, CancellationToken.NONE,
                (plan, prepared) -> new CandidateRanking.Assessment(prepared.cost().operationWork(), 1));
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, preferred);
        assertInstanceOf(VerificationResult.Verified.class,
                new ComputationOptimizer().reverify(request, candidate, CancellationToken.NONE));
    }

    @Test void rankingWorkCannotEscapeTheSharedSearchAllowance() {
        Expr x = new VariableExpr("x");
        var request = request(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.SUBTRACT, x, x), -1);
        assertInstanceOf(OptimizationResult.BudgetExceeded.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE,
                (plan, prepared) -> new CandidateRanking.Assessment(0, request.budget().maximumWork() * 2)));
    }

    @Test void scoreSavingsAloneMustNotClaimLessRuntimeWork() {
        Expr x = new VariableExpr("x");
        var request = request(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.SUBTRACT, x, x), -1);
        var result = new ComputationOptimizer().optimize(request, CancellationToken.NONE,
                (plan, prepared) -> new CandidateRanking.Assessment(prepared.cost().operationWork(), 1, 17));
        assertFalse(assertInstanceOf(OptimizationResult.Candidate.class, result).cost().estimatedRuntimeImprovement());
    }

    @Test void unchangedValueGraphsCanStillEliminateRepeatedSourceExecution() {
        Expr x = new VariableExpr("x");
        var request = request(NumericKind.INT, JavaExpressions.operation(NumericKind.INT, NumericOperation.MULTIPLY, x, x), -1);
        var ranking = new CandidateRanking() {
            @Override public Assessment assess(de.regelsuche.search.program.JointComputationPlan plan,
                    de.regelsuche.search.program.PreparedJointComputation prepared) { return new Assessment(1, 1, 1); }
            @Override public Assessment assessSource(de.regelsuche.search.program.JointComputationPlan plan,
                    de.regelsuche.search.program.PreparedJointComputation prepared) { return new Assessment(2, 1, 2); }
        };
        var result = assertInstanceOf(OptimizationResult.Candidate.class, new ComputationOptimizer().optimize(request, CancellationToken.NONE, ranking));
        assertTrue(result.cost().estimatedRuntimeImprovement());
        assertEquals(request.plan().outputExpressions(), result.plan().outputExpressions());
    }

    private static NumericExecutionCost.Estimate estimate(OptimizationRequest request) {
        return NumericExecutionCost.estimate(request, ComputationOptimizer.prepare(request.plan())).orElseThrow();
    }
    private static OptimizationRequest request(NumericKind kind, Expr expression, int bits) {
        var plan = new JointComputationPlan(Map.of("x", kind.type()), Map.of(),
                List.of(new JointComputationPlan.Output("out", kind.type(), expression)));
        var assumptions = bits < 0 ? Set.<SemanticAssumption>of() : Set.of(
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "test value"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", Integer.toString(bits), "test bound"));
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(kind), ComputationOptimizer.SEMANTICS_REVISION,
                assumptions, SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(1_000_000, 20_000, 64, 5000), CheckedPolicy.NONE);
    }
}
