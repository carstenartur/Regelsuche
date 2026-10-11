package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ComputationExplanationsTest {
    @Test void sharedModularDerivationContainsActualTermsAndUnchangedPremises() {
        var f = fixture();
        var result = ComputationExplanations.describe(f.request, f.candidate, CancellationToken.NONE);
        assertInstanceOf(VerificationResult.Verified.class, result.verification());
        var explanation = result.explanation().orElseThrow();
        assertEquals(f.candidate.evidence(), explanation.proof());
        assertEquals(f.request.assumptions(), explanation.assumptions());
        assertEquals(2, explanation.originalModularPowers());
        assertEquals(1, explanation.replacementModularPowers());
        assertTrue(explanation.replacement().steps().stream().anyMatch(s -> s.shared()
                && JavaExpressions.operationOf(s.expression()).orElse(null) == NumericOperation.MOD_POW));
        assertEquals(Set.of("left","right"), explanation.replacement().outputs().keySet());
        assertEquals(RuntimeObligations.GuardKind.NONE, explanation.obligations().guard());
        assertTrue(explanation.presentationWork() > 0);
    }
    @Test void stalePlanReceiptDoesNotReceiveAnExplanation() {
        var f=fixture();
        var wrong = f.candidate.plan().withOutputs(List.of(JavaExpressions.literal(BigInteger.ZERO),JavaExpressions.literal(BigInteger.ZERO)));
        var tampered = new OptimizationResult.Candidate(wrong, ComputationOptimizer.prepare(wrong), f.candidate.evidence(),
                f.candidate.obligations(), f.candidate.cost(), f.candidate.searchCompletion(), f.candidate.work());
        assertTrue(ComputationExplanations.describe(f.request,tampered,CancellationToken.NONE).explanation().isEmpty());
    }
    @Test void inconsistentScheduleIsRejectedBeforeItCanBeExplained() {
        var f=fixture();
        var wrong=f.candidate.plan().withOutputs(List.of(JavaExpressions.literal(BigInteger.ZERO),JavaExpressions.literal(BigInteger.ZERO)));
        var rejected=assertThrows(IllegalArgumentException.class,()->new OptimizationResult.Candidate(f.candidate.plan(),ComputationOptimizer.prepare(wrong),f.candidate.evidence(),
                f.candidate.obligations(),f.candidate.cost(),f.candidate.searchCompletion(),f.candidate.work()));
        assertEquals("PREPARED_PLAN_BINDING_DIFFERS",rejected.getMessage());
    }
    @Test void cancellationNeverLeavesAnApparentlyProvedExplanation() {
        var f=fixture();var result=ComputationExplanations.describe(f.request,f.candidate,()->true);
        assertInstanceOf(VerificationResult.Cancelled.class,result.verification());
        assertTrue(result.explanation().isEmpty());
    }
    @Test void explanationCollectionsCannotBeMutated() {
        var f=fixture();var e=ComputationExplanations.describe(f.request,f.candidate,CancellationToken.NONE).explanation().orElseThrow();
        assertThrows(UnsupportedOperationException.class,()->e.assumptions().clear());
        assertThrows(UnsupportedOperationException.class,()->e.original().steps().clear());
        assertThrows(UnsupportedOperationException.class,()->e.replacement().outputs().clear());
        assertThrows(UnsupportedOperationException.class,()->e.replacement().steps().getLast().arguments().clear());
    }
    static Fixture fixture() {
        Expr a=new VariableExpr("base"),e=new VariableExpr("exponent"),q=new VariableExpr("modulus");
        Expr one=JavaExpressions.literal(BigInteger.ONE), two=JavaExpressions.literal(BigInteger.TWO);
        var inputs=Map.of("base",NumericKind.BIG_INTEGER.type(),"exponent",NumericKind.BIG_INTEGER.type(),"modulus",NumericKind.BIG_INTEGER.type());
        var outputs=List.of(new JointComputationPlan.Output("left",NumericKind.BIG_INTEGER.type(),op(NumericOperation.MOD_POW,a,op(NumericOperation.ADD,e,one),q)),
            new JointComputationPlan.Output("right",NumericKind.BIG_INTEGER.type(),op(NumericOperation.MOD_POW,a,op(NumericOperation.ADD,op(NumericOperation.MULTIPLY,two,e),one),q)));
        var plan=new JointComputationPlan(inputs,Map.of(),outputs);
        var assumptions=new HashSet<SemanticAssumption>();
        for(String input:inputs.keySet()) {
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,input,"","captured receiver"));
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,input,"64","captured bound"));
        }
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE,"exponent","","proved exponent"));
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE,"modulus","","proved modulus"));
        var request=new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(NumericKind.BIG_INTEGER),ComputationOptimizer.SEMANTICS_REVISION,
            assumptions,SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,new OptimizationBudget(1_000_000,20000,64,5000),CheckedPolicy.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,new ComputationOptimizer().optimize(request,CancellationToken.NONE));
        return new Fixture(request,candidate);
    }
    private static Expr op(NumericOperation operation,Expr...args) {return JavaExpressions.operation(NumericKind.BIG_INTEGER,operation,args);}
    record Fixture(OptimizationRequest request,OptimizationResult.Candidate candidate) {}
}
