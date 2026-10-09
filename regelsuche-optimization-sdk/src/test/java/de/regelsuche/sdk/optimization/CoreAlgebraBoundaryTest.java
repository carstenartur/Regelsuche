package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreAlgebraBoundaryTest {
    private static final Expr X=new VariableExpr("x"),Y=new VariableExpr("y"),Z=new VariableExpr("z");
    @Test void nonzeroResultIsDiscoveredRatherThanOnlyWholeExpressionCancellation() {
        Expr source=op(NumericOperation.SUBTRACT,op(NumericOperation.MULTIPLY,X,op(NumericOperation.ADD,Y,Z)),op(NumericOperation.MULTIPLY,X,Y));
        var request=request(source);
        var optimizer=new ComputationOptimizer();
        var result=optimizer.optimize(request,CancellationToken.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        var expected=request.plan().withOutputs(List.of(op(NumericOperation.MULTIPLY,X,Z)));
        assertInstanceOf(VerificationResult.Verified.class,optimizer.verify(request(expected.outputExpressions().getFirst()),candidate.plan(),CancellationToken.NONE));
        assertTrue(candidate.cost().candidateCost().operationWork()<candidate.cost().sourceCost().operationWork(),candidate.cost().toString());
        assertEquals(Map.of("r",51),candidate.prepared().execute(Map.of("x",3,"y",11,"z",17)));
    }

    @Test void genericCatalogCanContributeWithoutAddingJavaMatcherCodeButCannotGrantProof() {
        var a=PatternExpr.var("A");var b=PatternExpr.var("B");
        var unsound=new PatternRewriteRule("false-equivalence-claim",PatternExpr.op(BinaryOperator.MUL,a,b),a,
                RewriteKind.SIMPLIFY,false,-1,true);
        var request=request(op(NumericOperation.MULTIPLY,X,Y));
        var generator=new CoreAlgebraCandidates(request,new VerificationWork(request,CancellationToken.NONE),List.of(unsound));
        var proposals=generator.generate(request.plan(),8);
        assertFalse(proposals.proposals().isEmpty());
        for(var proposal:proposals.proposals())
            assertFalse(new ComputationOptimizer().verify(request,request.plan().withExpression(proposal.expression()),CancellationToken.NONE)
                    instanceof VerificationResult.Verified,"A rule's equivalence flag is not a Java proof");
    }

    @Test void newFreeVariablesCannotEscapeTheRoundTrip() {
        var rule=new PatternRewriteRule("unbound",PatternExpr.op(BinaryOperator.MUL,PatternExpr.var("A"),PatternExpr.var("B")),
                PatternExpr.var("UNBOUND"),RewriteKind.SIMPLIFY,false,-1,true);
        var request=request(op(NumericOperation.MULTIPLY,X,Y));
        assertTrue(new CoreAlgebraCandidates(request,new VerificationWork(request,CancellationToken.NONE),List.of(rule))
                .generate(request.plan(),8).proposals().isEmpty());
    }

    @Test void coreCandidatesRespectDeterministicLimits() {
        var request=request(op(NumericOperation.SUBTRACT,op(NumericOperation.MULTIPLY,X,op(NumericOperation.ADD,Y,Z)),op(NumericOperation.MULTIPLY,X,Y)));
        var first=new CoreAlgebraCandidates(request,new VerificationWork(request,CancellationToken.NONE)).generate(request.plan(),1);
        var second=new CoreAlgebraCandidates(request,new VerificationWork(request,CancellationToken.NONE)).generate(request.plan(),1);
        assertTrue(first.proposals().size()<=1);
        assertEquals(first,second);
    }

    @Test void castsRemainTypedAndDistinct() {
        // The generic algebra bridge does not reinterpret division as real division.
        Expr unsafe=op(NumericOperation.DIVIDE,op(NumericOperation.MULTIPLY,X,JavaExpressions.literal(2)),JavaExpressions.literal(2));
        var request=request(op(NumericOperation.ADD,unsafe,Y));
        var wrong=request.plan().withOutputs(List.of(op(NumericOperation.ADD,X,Y)));
        assertFalse(new ComputationOptimizer().verify(request,wrong,CancellationToken.NONE) instanceof VerificationResult.Verified);
    }

    private static Expr op(NumericOperation operation,Expr... operands) {return JavaExpressions.operation(NumericKind.INT,operation,operands);}
    private static OptimizationRequest request(Expr expression) {
        var plan=new JointComputationPlan(Map.of("x",NumericKind.INT.type(),"y",NumericKind.INT.type(),"z",NumericKind.INT.type()),Map.of(),
                List.of(new JointComputationPlan.Output("r",NumericKind.INT.type(),expression)));
        return new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(NumericKind.INT),ComputationOptimizer.SEMANTICS_REVISION,Set.of(),
                SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,new OptimizationBudget(2_000_000L,20_000,64,5000L),CheckedPolicy.NONE);
    }
}
