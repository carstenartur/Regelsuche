package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.sdk.optimization.ValueArithmeticTest.*;
import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class SuccessfulInverseLawTest {
    private static final Expr A=new VariableExpr("a"),B=new VariableExpr("b"),Q=new VariableExpr("q");
    private static OptimizationRequest law(boolean witness,SafetyProfile profile) {
        Expr norm=mod(op(NumericOperation.ADD,mul(A,A),mul(B,B)),Q);
        Expr inverse=op(NumericOperation.MOD_INVERSE,norm,Q),x=mod(mul(A,inverse),Q),y=mod(mul(op(NumericOperation.SUBTRACT,Q,B),inverse),Q);
        var original=request(List.of(mod(op(NumericOperation.SUBTRACT,mul(A,x),mul(B,y)),Q),mod(op(NumericOperation.ADD,mul(A,y),mul(B,x)),Q),
            mod(op(NumericOperation.SUBTRACT,mul(x,A),mul(y,B)),Q),mod(op(NumericOperation.ADD,mul(x,B),mul(y,A)),Q)));
        var assumptions=new HashSet<>(original.assumptions());
        if(witness) assumptions.add(SemanticAssumption.successfulModularInverse(original.sourceTrace().occurrences().stream()
            .filter(o -> JavaExpressions.operationOf(o.expression()).orElse(null)==NumericOperation.MOD_INVERSE).findFirst().orElseThrow(),"conditional original inverse success"));
        return new OptimizationRequest(original.plan(),original.sourceTrace(),original.selectedKinds(),original.semanticsRevision(),assumptions,
            profile,original.goal(),original.budget(),original.checkedPolicy());
    }
    private static List<Expr> identity() { return List.of(mod(JavaExpressions.literal(BigInteger.ONE),Q),mod(JavaExpressions.literal(BigInteger.ZERO),Q),
        mod(JavaExpressions.literal(BigInteger.ONE),Q),mod(JavaExpressions.literal(BigInteger.ZERO),Q)); }

    @Test void compoundNormInverseLawIsConditionalOnTheExactSuccessfulSourceOperation() {
        var request=law(true,SafetyProfile.PRESERVE_JAVA); var optimizer=new ComputationOptimizer();
        var verified=assertInstanceOf(VerificationResult.Verified.class,optimizer.verify(request,request.plan().withOutputs(identity()),CancellationToken.NONE));
        assertTrue(verified.evidence().proofMethods().contains("CONDITIONAL_SUCCESSFUL_MODULAR_INVERSE"));
        var missing=law(false,SafetyProfile.PRESERVE_JAVA);
        assertInstanceOf(VerificationResult.Unsupported.class,optimizer.verify(missing,missing.plan().withOutputs(identity()),CancellationToken.NONE));
        var wrong=new ArrayList<>(identity()); wrong.set(0,mod(JavaExpressions.literal(BigInteger.TWO),Q));
        assertFalse(optimizer.verify(request,request.plan().withOutputs(wrong),CancellationToken.NONE) instanceof VerificationResult.Verified);
    }
    @Test void missingOrDriftedOccurrenceCommitmentIsRejectedBeforeProof() {
        var request=law(true,SafetyProfile.PRESERVE_JAVA);
        var witness=request.assumptions().stream().filter(a -> a.kind().name().equals("SUCCESSFUL_MODULAR_INVERSE")).findFirst().orElseThrow();
        for(String subject:List.of("missing",request.sourceTrace().occurrences().getFirst().sourceId())) {
            var assumptions=new HashSet<>(request.assumptions()); assumptions.remove(witness);
            assumptions.add(new SemanticAssumption(witness.kind(),subject,witness.parameter(),witness.provenance()));
            var changed=new OptimizationRequest(request.plan(),request.sourceTrace(),request.selectedKinds(),request.semanticsRevision(),assumptions,
                request.safetyProfile(),request.goal(),request.budget(),request.checkedPolicy());
            assertInstanceOf(VerificationResult.Unsupported.class,new ComputationOptimizer().verify(changed,changed.plan().withOutputs(identity()),CancellationToken.NONE));
        }
        var occurrences=new ArrayList<>(request.sourceTrace().occurrences());
        for(int i=0;i<occurrences.size();i++) if(occurrences.get(i).sourceId().equals(witness.subject())) {
            var old=occurrences.get(i); occurrences.set(i,new SourceEvaluationTrace.Occurrence(old.sourceId(),op(NumericOperation.MOD_INVERSE,A,Q),old.declaredKind(),old.evaluatedKind()));
        }
        var changed=new OptimizationRequest(request.plan(),new SourceEvaluationTrace(occurrences),request.selectedKinds(),request.semanticsRevision(),request.assumptions(),
            request.safetyProfile(),request.goal(),request.budget(),request.checkedPolicy());
        assertInstanceOf(VerificationResult.Unsupported.class,new ComputationOptimizer().verify(changed,changed.plan().withOutputs(identity()),CancellationToken.NONE));
    }
    @Test void runtimeCannotTreatAnAssertionAsAnObservedSuccessfulInverse() {
        var request=law(true,SafetyProfile.GUARDED_FALLBACK); var target=request.plan().withOutputs(identity()); var optimizer=new ComputationOptimizer();
        var proof=assertInstanceOf(VerificationResult.Verified.class,optimizer.verify(request,target,CancellationToken.NONE));
        var prepared=ComputationOptimizer.prepare(target); var original=ComputationOptimizer.prepare(request.plan());
        var candidate=new OptimizationResult.Candidate(target,prepared,proof.evidence(),proof.obligations(),
            new OptimizationResult.CostAssessment(1,0,0,0,original.cost(),prepared.cost(),true),OptimizationResult.SearchCompletion.IMPROVEMENT_FOUND,0);
        var inputs=new HashMap<String,Object>(); for(String name:request.plan().inputs().keySet()) inputs.put(name,BigInteger.ONE);
        inputs.put("q",BigInteger.valueOf(15));
        assertEquals(original.execute(inputs),optimizer.evaluateGuarded(request,candidate,inputs));
        inputs.put("a",BigInteger.ZERO); inputs.put("b",BigInteger.ZERO);
        assertThrows(ArithmeticException.class,() -> optimizer.evaluateGuarded(request,candidate,inputs));
    }
    @Test void witnessReplayAndRelationProofHonorCancellationAndWorkLimits() {
        var request=law(true,SafetyProfile.PRESERVE_JAVA); var optimizer=new ComputationOptimizer();
        assertInstanceOf(VerificationResult.Cancelled.class,optimizer.verify(request,request.plan().withOutputs(identity()),() -> true));
        var tiny=new OptimizationRequest(request.plan(),request.sourceTrace(),request.selectedKinds(),request.semanticsRevision(),request.assumptions(),request.safetyProfile(),
            request.goal(),new OptimizationBudget(100,128,32,5000),request.checkedPolicy());
        assertInstanceOf(VerificationResult.BudgetExceeded.class,optimizer.verify(tiny,tiny.plan().withOutputs(identity()),CancellationToken.NONE));
    }
    @Test void unitOfAnIndependentlyProvedPositivePowerImpliesUnitOfTheBase() {
        Expr norm=mod(op(NumericOperation.ADD,mul(A,A),mul(B,B)),Q);
        Expr power=mod(mul(mul(norm,norm),norm),Q);
        var initial=request(List.of(op(NumericOperation.MOD_INVERSE,power,Q)));
        var assumptions=new HashSet<>(initial.assumptions());
        assumptions.add(SemanticAssumption.successfulModularInverse(initial.sourceTrace().occurrences().getLast(),"retained successful power inverse"));
        var request=new OptimizationRequest(initial.plan(),initial.sourceTrace(),initial.selectedKinds(),initial.semanticsRevision(),assumptions,
            initial.safetyProfile(),initial.goal(),initial.budget(),initial.checkedPolicy());
        var verifier=new ModularUnitVerifier();
        assertTrue(verifier.verifyPowerRoot(request,norm,3,CancellationToken.NONE).verified());
        assertFalse(verifier.verifyPowerRoot(request,norm,2,CancellationToken.NONE).verified());
        assertFalse(verifier.verifyPowerRoot(request,op(NumericOperation.ADD,norm,JavaExpressions.literal(BigInteger.ONE)),3,CancellationToken.NONE).verified());
        assertFalse(verifier.verifyPowerRoot(request,norm,0,CancellationToken.NONE).verified());
        assertFalse(verifier.verifyPowerRoot(initial,norm,3,CancellationToken.NONE).verified());
        assertEquals("CANCELLED",verifier.verifyPowerRoot(request,norm,3,() -> true).reason());
    }
}
