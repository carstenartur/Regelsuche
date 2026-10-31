package example;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.iteration.FiniteIteration;
import de.regelsuche.ast.iteration.IndexRange;
import de.regelsuche.ast.iteration.IntegerArithmetic;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.sdk.optimization.*;
import de.regelsuche.sdk.optimization.iteration.FiniteIterationOptimizer;
import de.regelsuche.sdk.optimization.iteration.FiniteIterationVerifier;
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
