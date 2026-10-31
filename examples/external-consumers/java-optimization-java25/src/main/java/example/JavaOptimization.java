package example;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.IndexRange;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.sdk.optimization.*;
import de.regelsuche.sdk.optimization.iteration.FiniteIterationOptimizer;
import de.regelsuche.sdk.optimization.iteration.FiniteIterationVerifier;
import de.regelsuche.sdk.optimization.iteration.PolynomialSum;
import de.regelsuche.sdk.optimization.iteration.PolynomialSumOptimizer;
import de.regelsuche.sdk.optimization.iteration.PolynomialSumVerifier;
import de.regelsuche.sdk.optimization.iteration.PolynomialSumPlan;
import de.regelsuche.sdk.optimization.iteration.LinearIteration;
import de.regelsuche.sdk.optimization.iteration.LinearIterationOptimizer;
import de.regelsuche.sdk.optimization.iteration.LinearIterationVerifier;
import de.regelsuche.sdk.optimization.iteration.LinearIterationEvaluator;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Standalone consumer: ordinary published dependencies, no checkout project classes. */
public final class JavaOptimization {
    private JavaOptimization() {}
    public static void main(String[] args) {
        var optimizer = new ComputationOptimizer();
        Expr x = new VariableExpr("x");
        var source = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(), List.of(
            new JointComputationPlan.Output("out", NumericKind.INT.type(), JavaExpressions.operation(NumericKind.INT,
                NumericOperation.SUBTRACT, JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, x,
                    JavaExpressions.literal(1)), JavaExpressions.literal(1)))));
        var preserve = request(source, SafetyProfile.PRESERVE_JAVA);
        var candidate = requireCandidate(optimizer.optimize(preserve, CancellationToken.NONE));
        if (!(optimizer.reverify(preserve, candidate, CancellationToken.NONE) instanceof VerificationResult.Verified))
            throw new AssertionError("independent reverify rejected candidate");
        if (!Integer.valueOf(Integer.MAX_VALUE).equals(candidate.prepared().execute(Map.of("x", Integer.MAX_VALUE)).get("out")))
            throw new AssertionError("Java wraparound changed");
        System.out.println("optimization=VERIFIED");
        var explained = ComputationExplanations.describe(preserve, candidate, CancellationToken.NONE);
        var derivation = explained.explanation().orElseThrow().derivation().orElseThrow();
        if (derivation.steps().isEmpty()
                || !derivation.steps().getFirst().before().equals(source.expression())
                || !derivation.steps().getLast().after().equals(candidate.plan().expression()))
            throw new AssertionError("selected search path lost in standalone distribution");
        System.out.println("derivation=REPLAYED_SELECTED_PATH");
        var checked = request(source, SafetyProfile.CHECKED_THROW);
        var checkedCandidate = requireCandidate(optimizer.optimize(checked, CancellationToken.NONE));
        try {
            optimizer.evaluateChecked(checked, checkedCandidate, Map.of("x", Integer.MAX_VALUE));
            throw new AssertionError("eliminated original overflow was not checked");
        } catch (ArithmeticException expected) {
            System.out.println("checked=ORIGINAL_OVERFLOW_DETECTED");
        }
        var domains = IntegralDomainAnalysis.compare(source, SourceEvaluationTrace.fromPlan(source),
                source.withOutputs(List.of(x)));
        if (!(domains instanceof IntegralDomainAnalysis.Verified verified)
                || !verified.source().inputRange().maximum().equals(java.math.BigInteger.valueOf(Integer.MAX_VALUE - 1L))
                || !verified.candidate().inputRange().maximum().equals(java.math.BigInteger.valueOf(Integer.MAX_VALUE)))
            throw new AssertionError("shared exact integral domains unavailable in standalone SDK");
        System.out.println("domains=ORIGINAL_AND_CANDIDATE_VERIFIED");
        verifyFiniteSum();
        verifyRuntimeSeries();
    }
    private static void verifyRuntimeSeries() {
        var namespace=UUID.fromString("f0b26fe5-b139-4677-bc37-9bc8c59b6f8f");
        var index=new SymbolId(namespace,1); var state=new SymbolId(namespace,2);
        var sum=new PolynomialSum(index,Set.of(),new NumberExpr(1),BigInteger.ONE,
                VariableExpr.scoped(index),new NumberExpr(0),IntegerArithmetic.JAVA_INT);
        var plan=new PolynomialSumOptimizer().search(sum,OptimizationBudget.DEFAULT,CancellationToken.NONE).plan().orElseThrow();
        var checker=new PolynomialSumVerifier();
        if(!checker.verify(sum,plan,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified()
                || !plan.evaluate(BigInteger.valueOf(50000),Map.of(),OptimizationBudget.DEFAULT,CancellationToken.NONE)
                        .equals(BigInteger.valueOf(1250025000)))
            throw new AssertionError("runtime polynomial sum overflowed or failed verification");
        var tampered=new PolynomialSumPlan(sum,new NumberExpr(1),plan.coefficients());
        if(checker.verify(sum,tampered,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified())
            throw new AssertionError("runtime polynomial verifier accepted modified initial state");
        var recurrence=new LinearIteration(index,new NumberExpr(0),BigInteger.ONE,new NumberExpr(10),
                List.of(new LinearIteration.State(state,new NumberExpr(1))),Set.of(),
                List.of(new LinearIteration.Update(state,new BinaryExpr(
                        new BinaryExpr(new NumberExpr(2),BinaryOperator.MUL,VariableExpr.scoped(state)),
                        BinaryOperator.ADD,new NumberExpr(1)))),
                LinearIteration.UpdateOrder.SEQUENTIAL,IntegerArithmetic.JAVA_LONG);
        var candidate=new LinearIterationOptimizer().search(recurrence,OptimizationBudget.DEFAULT,CancellationToken.NONE)
                .candidate().orElseThrow();
        if(!new LinearIterationVerifier().verify(recurrence,candidate,OptimizationBudget.DEFAULT,CancellationToken.NONE).verified()
                || !new LinearIterationEvaluator().evaluate(recurrence,candidate,Map.of(),OptimizationBudget.DEFAULT,CancellationToken.NONE)
                        .values().orElseThrow().equals(List.of(BigInteger.valueOf(2047))))
            throw new AssertionError("affine iteration unavailable in standalone SDK");
        if(new LinearIterationVerifier().verifyResult(recurrence,candidate,Map.of(),List.of(BigInteger.ZERO),
                OptimizationBudget.DEFAULT,CancellationToken.NONE).verified())
            throw new AssertionError("linear verifier accepted changed output");
        System.out.println("series=VERIFIED_RUNTIME_POLYNOMIAL_AND_AFFINE");
    }
    private static void verifyFiniteSum() {
        var index = new SymbolId(UUID.fromString("1dbf1391-d993-46c1-9cba-7d379b1e7f41"), 1);
        var sum = new FiniteIteration.Sum(new IndexRange(BigInteger.ONE, BigInteger.ONE,
                BigInteger.valueOf(100)), index, Set.of(), VariableExpr.scoped(index), IntegerArithmetic.JAVA_INT);
        var candidate = new FiniteIterationOptimizer().search(sum, OptimizationBudget.DEFAULT,
                CancellationToken.NONE).candidate().orElseThrow();
        var checker = new FiniteIterationVerifier();
        if (!new NumberExpr(5050).equals(candidate.result())
                || !checker.verify(sum, candidate, OptimizationBudget.DEFAULT, CancellationToken.NONE).verified())
            throw new AssertionError("finite sum is not independently verified in standalone SDK");
        if (checker.verify(sum, new NumberExpr(5051), candidate.certificate(),
                OptimizationBudget.DEFAULT, CancellationToken.NONE).verified())
            throw new AssertionError("finite sum checker accepted a changed emitted result");
        System.out.println("iteration=VERIFIED_TELESCOPING_5050");
    }
    private static OptimizationRequest request(JointComputationPlan plan, SafetyProfile profile) {
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.INT),
            ComputationOptimizer.SEMANTICS_REVISION, Set.of(), profile, OptimizationGoal.READABILITY,
            OptimizationBudget.DEFAULT, profile==SafetyProfile.CHECKED_THROW?CheckedPolicy.EXPLICIT_DEFAULT:CheckedPolicy.NONE);
    }
    private static OptimizationResult.Candidate requireCandidate(OptimizationResult result) {
        if (result instanceof OptimizationResult.Candidate candidate) return candidate;
        throw new AssertionError(result);
    }
}
