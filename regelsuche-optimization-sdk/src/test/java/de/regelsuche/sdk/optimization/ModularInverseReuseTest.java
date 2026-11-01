package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.sdk.optimization.ValueArithmeticTest.*;
import de.regelsuche.ast.*;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularInverseReuseTest {
    private static final Expr X=new VariableExpr("a"), Q=new VariableExpr("q");
    private static Expr inverse(Expr value) { return op(NumericOperation.valueOf("MOD_INVERSE"),value,Q); }
    private static OptimizationRequest units(List<Expr> outputs) {
        var request=request(outputs); var assumptions=new HashSet<>(request.assumptions());
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.valueOf("MODULAR_UNIT_INPUT"),"a","q","original successful inverse"));
        return new OptimizationRequest(request.plan(),request.sourceTrace(),request.selectedKinds(),request.semanticsRevision(),assumptions,
            request.safetyProfile(),request.goal(),request.budget(),request.checkedPolicy());
    }
    @Test void compositeModulusUnitReusesRetainedSuccessfulInverse() {
        Expr square=mul(X,X), known=inverse(square);
        var request=units(List.of(inverse(X),known));
        var optimizer=new ComputationOptimizer();
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,optimizer.optimize(request,CancellationToken.NONE,(plan,prepared) ->
            new CandidateRanking.Assessment(1000*prepared.nodes().stream().filter(n -> JavaExpressions.operationOf(n.expression()).orElse(null)==NumericOperation.valueOf("MOD_INVERSE")).count()+prepared.nodes().size(),prepared.nodes().size())));
        assertInstanceOf(VerificationResult.Verified.class,optimizer.reverify(request,candidate,CancellationToken.NONE));
        assertEquals(1,candidate.prepared().nodes().stream().filter(n -> JavaExpressions.operationOf(n.expression()).orElse(null)==NumericOperation.valueOf("MOD_INVERSE")).count());
        assertEquals(request.sourceTrace(),candidate.obligations().originalTrace());
        for(int modulus:new int[]{1,8,15,21}) for(int x:new int[]{-11,-2,1,2,4,11}) {
            var a=BigInteger.valueOf(x); var q=BigInteger.valueOf(modulus);
            if(!a.gcd(q).equals(BigInteger.ONE)) continue;
            var inputs=new HashMap<String,Object>(); for(String input:request.plan().inputs().keySet()) inputs.put(input,BigInteger.ONE);
            inputs.put("a",a); inputs.put("q",q);
            RuntimeChecks.validateAssumptions(request,inputs);
            assertEquals(ComputationOptimizer.prepare(request.plan()).execute(inputs),candidate.prepared().execute(inputs));
        }
    }
    @Test void independentInverseProofRejectsWrongPowerMissingUnitAndUnretainedInverse() {
        Expr known=inverse(mul(X,X)); var request=units(List.of(inverse(X),known));
        var optimizer=new ComputationOptimizer();
        assertInstanceOf(VerificationResult.Verified.class,optimizer.verify(request,request.plan().withOutputs(List.of(mod(mul(X,known),Q),known)),CancellationToken.NONE));
        assertFalse(optimizer.verify(request,request.plan().withOutputs(List.of(mod(mul(mul(X,X),known),Q),known)),CancellationToken.NONE) instanceof VerificationResult.Verified);
        var withoutKnown=units(List.of(inverse(X)));
        assertFalse(optimizer.verify(withoutKnown,withoutKnown.plan().withOutputs(List.of(mod(mul(X,known),Q))),CancellationToken.NONE) instanceof VerificationResult.Verified);
        var missingUnit=request(List.of(inverse(X),known));
        assertInstanceOf(VerificationResult.Unsupported.class,optimizer.verify(missingUnit,missingUnit.plan(),CancellationToken.NONE));
        var inputs=new HashMap<String,Object>(); for(String input:request.plan().inputs().keySet()) inputs.put(input,BigInteger.ONE);
        inputs.put("a",BigInteger.valueOf(3)); inputs.put("q",BigInteger.valueOf(15));
        assertThrows(IllegalArgumentException.class,() -> RuntimeChecks.validateAssumptions(request,inputs));
    }
}
