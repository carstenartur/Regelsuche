package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** No target formula is supplied to optimize: the input itself is the search problem. */
class GeneralAlgebraSearchTest {
    private static final Expr X = new VariableExpr("x"), Y = new VariableExpr("y"), Z = new VariableExpr("z");
    private static final Expr W = new VariableExpr("w");

    @Test void exposesCancellationByExpandingAnInitiallyUnhelpfulProduct() {
        for (var kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            Expr source = op(kind, NumericOperation.SUBTRACT,
                    op(kind, NumericOperation.MULTIPLY, X, op(kind, NumericOperation.ADD, Y, Z)),
                    op(kind, NumericOperation.MULTIPLY, X, Y));
            qualify(kind, source);
        }
    }

    @Test void composesAlgebraAcrossNestedUnknownOperands() {
        var k = NumericKind.INT;
        Expr source = op(k, NumericOperation.SUBTRACT,
                op(k, NumericOperation.MULTIPLY, X, op(k, NumericOperation.ADD,
                        op(k, NumericOperation.MULTIPLY, Y, Z), W)),
                op(k, NumericOperation.MULTIPLY, X, op(k, NumericOperation.MULTIPLY, Y, Z)));
        qualify(k, source);
    }

    @Test void generatedOperandTreesUseTheSameUnconfiguredOptimizer() {
        var k = NumericKind.INT;
        var random = new Random(0x6ca570L);
        for (int i = 0; i < 6; i++) {
            Expr a = tree(k, random, 1), b = tree(k, random, 1), c = tree(k, random, 1);
            Expr source = op(k, NumericOperation.SUBTRACT,
                    op(k, NumericOperation.MULTIPLY, a, op(k, NumericOperation.ADD, b, c)),
                    op(k, NumericOperation.MULTIPLY, a, b));
            qualify(k, source);
        }
    }

    @Test void rationalCancellationDoesNotChangeJavaIntegerDivision() {
        var k = NumericKind.INT;
        Expr source = op(k, NumericOperation.DIVIDE,
                op(k, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2)), JavaExpressions.literal(2));
        var request = request(k, source);
        var result = new ComputationOptimizer().optimize(request, CancellationToken.NONE);
        if (result instanceof OptimizationResult.Candidate c) {
            assertInstanceOf(VerificationResult.Verified.class, new ComputationOptimizer().reverify(request,c,CancellationToken.NONE));
            var inputs = inputs(k,Integer.MAX_VALUE,3,5,7);
            assertEquals(ComputationOptimizer.prepare(request.plan()).execute(inputs), c.prepared().execute(inputs));
        }
        assertFalse(new ComputationOptimizer().verify(request,request.plan().withOutputs(List.of(X)),CancellationToken.NONE)
                instanceof VerificationResult.Verified);
    }

    @Test void floatingPointReassociationRemainsUnproved() {
        var k = NumericKind.DOUBLE;
        Expr source = op(k, NumericOperation.SUBTRACT, op(k, NumericOperation.ADD, X, Y), X);
        var plan = plan(k,source);
        var request = new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(k),
                ComputationOptimizer.SEMANTICS_REVISION,Set.of(new SemanticAssumption(
                        SemanticAssumption.Kind.NO_NAN_PAYLOAD_OBSERVATION,"result","","test compares Java numeric values")),
                SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                new OptimizationBudget(1_000_000,20_000,64,5000),CheckedPolicy.NONE);
        assertFalse(new ComputationOptimizer().verify(request,plan.withOutputs(List.of(Y)),CancellationToken.NONE)
                instanceof VerificationResult.Verified);
    }

    @Test void candidateProductionActuallyUsesTheCoreInventory() {
        var k=NumericKind.INT;
        Expr source=op(k,NumericOperation.SUBTRACT,
                op(k,NumericOperation.MULTIPLY,X,op(k,NumericOperation.ADD,Y,Z)),
                op(k,NumericOperation.MULTIPLY,X,Y));
        var request=request(k,source);
        var disabled=new JavaAlgebraCandidates(new VerificationWork(request,CancellationToken.NONE),List.of(),1_000_000);
        assertTrue(disabled.generate(request.plan(),64).proposals().isEmpty());
        var enabled=new JavaAlgebraCandidates(new VerificationWork(request,CancellationToken.NONE),1_000_000);
        var proposals=enabled.generate(request.plan(),64);
        assertFalse(proposals.proposals().isEmpty());
        assertTrue(proposals.proposals().stream().allMatch(p->p.rule().startsWith("core-algebra/")));
        assertTrue(proposals.proposals().stream().anyMatch(p->p.rule().contains("distribute")),proposals.toString());
    }

    @Test void nativeGenerationAccountsForScopeCleanupAndHonorsCancellation() {
        var kind=NumericKind.INT;
        Expr source=op(kind,NumericOperation.SUBTRACT,
                op(kind,NumericOperation.MULTIPLY,X,op(kind,NumericOperation.ADD,Y,Z)),
                op(kind,NumericOperation.MULTIPLY,X,Y));
        var request=request(kind,source);
        var work=new VerificationWork(request,CancellationToken.NONE);
        var generator=new JavaAlgebraCandidates(work,1_000_000);
        long before=work.used();
        var generated=generator.generate(request.plan(),64);
        assertEquals(work.used()-before,generated.work(),"Native scope cleanup is part of generation cost");
        var canceled=new JavaAlgebraCandidates(new VerificationWork(request,()->true),1_000_000);
        var failure=assertThrows(VerificationWork.Stopped.class,()->canceled.generate(request.plan(),64));
        assertTrue(failure.cancelled);
    }

    @Test void arbitraryGeneratedProgramsNeedNoPerProgramRuleOrTarget() {
        var random=new Random(0x584197L);
        var optimizer=new ComputationOptimizer();
        for(int i=0;i<24;i++) {
            var k=i%2==0?NumericKind.INT:NumericKind.LONG;
            Expr source=randomProgram(k,random,3);
            var request=request(k,source);
            var result=optimizer.optimize(request,CancellationToken.NONE);
            if(result instanceof OptimizationResult.Candidate candidate) {
                assertInstanceOf(VerificationResult.Verified.class,optimizer.reverify(request,candidate,CancellationToken.NONE));
                var original=ComputationOptimizer.prepare(request.plan());
                for(int sample=0;sample<64;sample++) {
                    var input=inputs(k,random.nextLong(),random.nextLong(),random.nextLong(),random.nextLong());
                    assertEquals(original.execute(input),candidate.prepared().execute(input));
                }
            } else {
                assertFalse(result instanceof OptimizationResult.Cancelled, result.toString());
            }
        }
    }
    private static Expr randomProgram(NumericKind k,Random random,int depth) {
        if(depth==0)return new Expr[]{X,Y,Z,W}[random.nextInt(4)];
        var operation=new NumericOperation[]{NumericOperation.ADD,NumericOperation.SUBTRACT,NumericOperation.MULTIPLY}[random.nextInt(3)];
        return op(k,operation,randomProgram(k,random,depth-1),randomProgram(k,random,depth-1));
    }

    private static Expr tree(NumericKind k, Random r, int depth) {
        Expr[] leaves={X,Y,Z,W};
        if (depth==0) return leaves[r.nextInt(leaves.length)];
        return op(k,r.nextBoolean()?NumericOperation.ADD:NumericOperation.SUBTRACT,
                tree(k,r,depth-1),tree(k,r,depth-1));
    }
    private static void qualify(NumericKind k, Expr source) {
        var request=request(k,source);
        var optimizer=new ComputationOptimizer();
        var result=optimizer.optimize(request,CancellationToken.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,result,result.toString());
        assertInstanceOf(VerificationResult.Verified.class,optimizer.reverify(request,candidate,CancellationToken.NONE));
        assertTrue(candidate.cost().candidateScore()<candidate.cost().sourceScore(),candidate.cost().toString());
        var original=ComputationOptimizer.prepare(request.plan());
        long[] edges={0,1,-1,Integer.MIN_VALUE,Integer.MAX_VALUE,Long.MIN_VALUE,Long.MAX_VALUE};
        for (long x:edges) for(long y:edges) {
            var input=inputs(k,x,y,17,-31);
            assertEquals(original.execute(input),candidate.prepared().execute(input),input.toString());
        }
        var random=new Random(41);
        for(int i=0;i<128;i++) {
            var input=inputs(k,random.nextLong(),random.nextLong(),random.nextLong(),random.nextLong());
            assertEquals(original.execute(input),candidate.prepared().execute(input));
        }
    }
    private static Map<String,Object> inputs(NumericKind k,long x,long y,long z,long w) {
        return Map.of("x",number(k,x),"y",number(k,y),"z",number(k,z),"w",number(k,w));
    }
    private static Object number(NumericKind k,long n) {
        if(k==NumericKind.INT)return Integer.valueOf((int)n);
        return Long.valueOf(n);
    }
    private static Expr op(NumericKind k,NumericOperation op,Expr... args){return JavaExpressions.operation(k,op,args);}
    private static JointComputationPlan plan(NumericKind k,Expr e){return new JointComputationPlan(
            Map.of("x",k.type(),"y",k.type(),"z",k.type(),"w",k.type()),Map.of(),
            List.of(new JointComputationPlan.Output("result",k.type(),e)));}
    private static OptimizationRequest request(NumericKind k,Expr e){var p=plan(k,e);return new OptimizationRequest(
            p,SourceEvaluationTrace.fromPlan(p),Set.of(k),ComputationOptimizer.SEMANTICS_REVISION,Set.of(),
            SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
            new OptimizationBudget(1_000_000,20_000,64,5000),CheckedPolicy.NONE);}
}
