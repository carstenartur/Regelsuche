package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The final proof must compose independently checked arithmetic and word identities. */
class MixedTheoryProofTest {
    @Test void xorOfEquivalentArithmeticTermsIsProvedInsideAddition() {
        for (var kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            Expr x = new VariableExpr("x");
            Expr a = op(kind, NumericOperation.SUBTRACT, op(kind, NumericOperation.ADD, x, number(kind,1)), number(kind,1));
            Expr b = op(kind, NumericOperation.ADD, a, number(kind,0));
            Expr source = op(kind, NumericOperation.ADD, op(kind, NumericOperation.XOR, a,b), number(kind,7));
            var request = request(kind, source);
            var optimizer = new ComputationOptimizer();
            var verified = optimizer.verify(request, plan(kind,number(kind,7)), CancellationToken.NONE);
            assertInstanceOf(VerificationResult.Verified.class, verified, verified.toString());
            var result = optimizer.optimize(request, CancellationToken.NONE);
            var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
            assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request,candidate,CancellationToken.NONE));
            assertEquals(List.of(number(kind,7)),candidate.plan().outputExpressions());
        }
    }
    @Test void independentArithmeticAtomsAreNotMerged() {
        Expr x = new VariableExpr("x"), y = new VariableExpr("y");
        var kind = NumericKind.INT;
        var inputs = Map.of("x",kind.type(),"y",kind.type());
        Expr source = op(kind,NumericOperation.ADD,op(kind,NumericOperation.XOR,x,y),number(kind,7));
        var plan = new JointComputationPlan(inputs,Map.of(),List.of(new JointComputationPlan.Output("result",kind.type(),source)));
        var request = new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(kind),ComputationOptimizer.SEMANTICS_REVISION,
            Set.of(),SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
        var target = plan.withOutputs(List.of(number(kind,7)));
        assertFalse(new ComputationOptimizer().verify(request,target,CancellationToken.NONE) instanceof VerificationResult.Verified);
    }
    @Test void overflowSensitiveDivisionIsNotAnEquivalentBitwiseAtom() {
        var kind = NumericKind.INT;
        Expr x = new VariableExpr("x");
        Expr lossy = op(kind,NumericOperation.DIVIDE,op(kind,NumericOperation.MULTIPLY,x,number(kind,2)),number(kind,2));
        Expr source = op(kind,NumericOperation.ADD,op(kind,NumericOperation.XOR,lossy,x),number(kind,7));
        var verified = new ComputationOptimizer().verify(request(kind,source),plan(kind,number(kind,7)),CancellationToken.NONE);
        assertFalse(verified instanceof VerificationResult.Verified,verified.toString());
    }
    private static Expr op(NumericKind kind,NumericOperation operation,Expr... values) { return JavaExpressions.operation(kind,operation,values); }
    private static Expr number(NumericKind kind,int value) { return kind==NumericKind.LONG?JavaExpressions.literal((long)value):JavaExpressions.literal(value); }
    private static JointComputationPlan plan(NumericKind kind,Expr expression) {
        return new JointComputationPlan(Map.of("x",kind.type()),Map.of(),List.of(new JointComputationPlan.Output("result",kind.type(),expression)));
    }
    private static OptimizationRequest request(NumericKind kind,Expr source) {
        var plan=plan(kind,source);
        return new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(kind),ComputationOptimizer.SEMANTICS_REVISION,Set.of(),
            SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
    }
}
