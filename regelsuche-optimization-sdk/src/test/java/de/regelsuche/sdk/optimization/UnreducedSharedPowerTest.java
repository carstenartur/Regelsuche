package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class UnreducedSharedPowerTest {
    @Test void twoAffinePowersBecomeOneWithoutAssumingANormalizedBase() {
        Expr a = new VariableExpr("base"), e = new VariableExpr("exponent"), q = new VariableExpr("modulus");
        Expr one = JavaExpressions.literal(BigInteger.ONE);
        var outputs = List.of(
            new JointComputationPlan.Output("first", NumericKind.BIG_INTEGER.type(), op(NumericOperation.MOD_POW, a, op(NumericOperation.ADD, e, one), q)),
            new JointComputationPlan.Output("second", NumericKind.BIG_INTEGER.type(), op(NumericOperation.MOD_POW, a, op(NumericOperation.ADD, op(NumericOperation.MULTIPLY, JavaExpressions.literal(BigInteger.TWO), e), one), q)));
        var plan = new JointComputationPlan(Map.of("base", NumericKind.BIG_INTEGER.type(), "exponent", NumericKind.BIG_INTEGER.type(), "modulus", NumericKind.BIG_INTEGER.type()), Map.of(), outputs);
        var assumptions = new HashSet<SemanticAssumption>();
        for (String input : plan.inputs().keySet()) {
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, input, "", "exact captured value"));
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, input, "64", "captured bound"));
        }
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE, "exponent", "", "proved exponent"));
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE, "modulus", "", "proved modulus"));
        var request = new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.BIG_INTEGER), ComputationOptimizer.SEMANTICS_REVISION,
            assumptions, SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1_000_000L,20_000,64,5000L), CheckedPolicy.NONE);
        var optimizer = new ComputationOptimizer();
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertEquals(1, candidate.prepared().nodes().stream().filter(n -> JavaExpressions.operationOf(n.expression()).orElse(null) == NumericOperation.MOD_POW).count(), candidate.plan().toString());
        for (long base : new long[]{Long.MIN_VALUE,-202,-101,-10,-1,0,1,101,202,Long.MAX_VALUE})
            for (long exponent : new long[]{0,1,2,37}) for (long modulus : new long[]{1,2,17,101}) {
                var values = Map.<String,Object>of("base",BigInteger.valueOf(base),"exponent",BigInteger.valueOf(exponent),"modulus",BigInteger.valueOf(modulus));
                assertEquals(ComputationOptimizer.prepare(plan).execute(values),candidate.prepared().execute(values),values.toString());
            }
    }
    private static Expr op(NumericOperation op, Expr... args) { return JavaExpressions.operation(NumericKind.BIG_INTEGER,op,args); }
}
