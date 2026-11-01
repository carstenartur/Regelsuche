package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplacementDagTraceTest {
    @Test void dagDigestsIgnoreObjectSharingButBindOperationsAndOccurrenceOrder() {
        Expr a=new VariableExpr("a"),shared=JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.ADD,a,a);
        Expr product=JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MULTIPLY,shared,shared);
        Expr copied=JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.MULTIPLY,
            JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.ADD,a,a),JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.ADD,a,a));
        var request=ValueArithmeticTest.request(List.of(product));
        assertEquals(EvidenceHashes.plan(request.plan()),EvidenceHashes.plan(request.plan().withOutputs(List.of(copied))));
        var prepared=ComputationOptimizer.prepare(request.plan());
        var work=new VerificationWork(request,CancellationToken.NONE);
        var hashes=new EvidenceHashes.Capture(request,prepared,work).finish(request.plan(),prepared);
        assertEquals(EvidenceHashes.plan(request.plan()),hashes.source());
        assertEquals(hashes.source(),hashes.target());
        assertEquals(EvidenceHashes.trace(request.sourceTrace()),hashes.trace());
        assertTrue(work.used()>0);
        assertNotEquals(EvidenceHashes.plan(request.plan()),EvidenceHashes.plan(request.plan().withOutputs(List.of(shared))));
        var first=new SourceEvaluationTrace.Occurrence("first",shared,NumericKind.BIG_INTEGER,NumericKind.BIG_INTEGER);
        var second=new SourceEvaluationTrace.Occurrence("second",shared,NumericKind.BIG_INTEGER,NumericKind.BIG_INTEGER);
        assertNotEquals(EvidenceHashes.trace(new SourceEvaluationTrace(List.of(first,second))),EvidenceHashes.trace(new SourceEvaluationTrace(List.of(second,first))));
    }
    @Test void replacementTraceUsesPreparedDagWhileOriginalOccurrencesRemainDistinct() {
        Expr value=new VariableExpr("a"); var occurrences=new ArrayList<SourceEvaluationTrace.Occurrence>();
        for(int i=0;i<16;i++) {
            value=JavaExpressions.operation(NumericKind.BIG_INTEGER,NumericOperation.ADD,value,value);
            occurrences.add(new SourceEvaluationTrace.Occurrence("original-"+i,value,NumericKind.BIG_INTEGER,NumericKind.BIG_INTEGER));
        }
        occurrences.add(new SourceEvaluationTrace.Occurrence("repeated-original",value,NumericKind.BIG_INTEGER,NumericKind.BIG_INTEGER));
        var plan=new JointComputationPlan(Map.of("a",NumericKind.BIG_INTEGER.type()),Map.of(),List.of(new JointComputationPlan.Output("out",NumericKind.BIG_INTEGER.type(),value)));
        var request=new OptimizationRequest(plan,new SourceEvaluationTrace(occurrences),Set.of(NumericKind.BIG_INTEGER),ComputationOptimizer.SEMANTICS_REVISION,
            Set.of(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,"a","","exact capture"),
                new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,"a","32","source bound")),
            SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
        var optimizer=new ComputationOptimizer();
        var verification=optimizer.verify(request,plan,CancellationToken.NONE);
        var result=assertInstanceOf(VerificationResult.Verified.class,verification,verification.toString());
        assertEquals(17,result.obligations().originalTrace().occurrences().size());
        assertEquals(16,result.obligations().replacementTrace().occurrences().size());
        assertEquals(BigInteger.valueOf(65536),RuntimeChecks.trace(plan,result.obligations().replacementTrace(),Map.of("a",BigInteger.ONE),result.obligations()).get("out"));
        assertInstanceOf(VerificationResult.Cancelled.class,optimizer.verify(request,plan,() -> true));
        var tiny=new OptimizationRequest(plan,request.sourceTrace(),request.selectedKinds(),request.semanticsRevision(),request.assumptions(),request.safetyProfile(),
            request.goal(),new OptimizationBudget(100,128,32,5000),request.checkedPolicy());
        assertInstanceOf(VerificationResult.BudgetExceeded.class,optimizer.verify(tiny,plan,CancellationToken.NONE));
        var incomplete=new OptimizationRequest(plan,new SourceEvaluationTrace(occurrences.subList(1,occurrences.size())),request.selectedKinds(),request.semanticsRevision(),request.assumptions(),
            request.safetyProfile(),request.goal(),request.budget(),request.checkedPolicy());
        assertInstanceOf(VerificationResult.Unsupported.class,optimizer.verify(incomplete,plan,CancellationToken.NONE));
    }
}
