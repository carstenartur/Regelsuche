package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueArithmeticTest {
    private static final Expr A = new VariableExpr("a"), B = new VariableExpr("b"),
        C = new VariableExpr("c"), D = new VariableExpr("d"), Q = new VariableExpr("q");

    @Test void jointBilinearCandidateUsesThreeProductsWithIndependentProof() {
        for (boolean modular : List.of(false, true)) {
            Expr real = op(NumericOperation.SUBTRACT, mul(A,C), mul(B,D));
            Expr imaginary = op(NumericOperation.ADD, mul(A,D), mul(B,C));
            var request = request(modular ? List.of(mod(real,Q),mod(imaginary,Q)) : List.of(real,imaginary));
            var optimizer = new ComputationOptimizer();
            var result = optimizer.optimize(request, CancellationToken.NONE, (plan, prepared) ->
                new CandidateRanking.Assessment(products(plan)*1000 + prepared.nodes().size(),prepared.nodes().size()));
            var candidate = assertInstanceOf(OptimizationResult.Candidate.class,result);
            assertEquals(3, products(candidate.plan()));
            assertInstanceOf(VerificationResult.Verified.class,optimizer.reverify(request,candidate,CancellationToken.NONE));
            assertTrue(candidate.evidence().proofMethods().stream().anyMatch(m -> m.contains("POLYNOMIAL")));
            for (int a : new int[]{-11,0,7}) for (int b : new int[]{-9,2}) for (int q : new int[]{1,2,17}) {
                var inputs = Map.<String,Object>of("a",BigInteger.valueOf(a),"b",BigInteger.valueOf(b),
                    "c",BigInteger.valueOf(5),"d",BigInteger.valueOf(-3),"q",BigInteger.valueOf(q));
                assertEquals(ComputationOptimizer.prepare(request.plan()).execute(inputs),candidate.prepared().execute(inputs));
            }
        }
    }

    @Test void modularPolynomialProofErasesOnlyTheSameModulusThroughRingOperations() {
        Expr source = mod(op(NumericOperation.ADD,op(NumericOperation.NEGATE,mul(mod(A,Q),mod(B,Q))),mod(C,Q)),Q);
        Expr target = mod(op(NumericOperation.SUBTRACT,C,mul(A,B)),Q);
        var request = request(List.of(source));
        var optimizer = new ComputationOptimizer();
        assertInstanceOf(VerificationResult.Verified.class,optimizer.verify(request,request.plan().withOutputs(List.of(target)),CancellationToken.NONE));
        Expr wrong = mod(op(NumericOperation.ADD,mul(mod(A,JavaExpressions.literal(BigInteger.TWO)),B),C),Q);
        assertFalse(optimizer.verify(request,request.plan().withOutputs(List.of(wrong)),CancellationToken.NONE) instanceof VerificationResult.Verified);
        Expr wrongSign = mod(op(NumericOperation.ADD,mul(A,B),C),Q);
        assertFalse(optimizer.verify(request,request.plan().withOutputs(List.of(wrongSign)),CancellationToken.NONE) instanceof VerificationResult.Verified);
        var noPositive = new HashSet<>(request.assumptions()); noPositive.removeIf(a -> a.kind()==SemanticAssumption.Kind.POSITIVE);
        var invalid = new OptimizationRequest(request.plan(),request.sourceTrace(),request.selectedKinds(),request.semanticsRevision(),noPositive,
            request.safetyProfile(),request.goal(),request.budget(),request.checkedPolicy());
        assertInstanceOf(VerificationResult.Unsupported.class,optimizer.verify(invalid,request.plan().withOutputs(List.of(target)),CancellationToken.NONE));
    }

    @Test void nonnegativePrimitiveRemainderUsesMaskButUnknownNegativeInputDoesNot() {
        for (NumericKind kind : List.of(NumericKind.INT,NumericKind.LONG)) {
            Expr divisor = kind==NumericKind.INT ? JavaExpressions.literal(8) : JavaExpressions.literal(8L);
            Expr mask = kind==NumericKind.INT ? JavaExpressions.literal(7) : JavaExpressions.literal(7L);
            Expr remainder = JavaExpressions.operation(kind,NumericOperation.REMAINDER,A,divisor);
            Expr masked = JavaExpressions.operation(kind,NumericOperation.AND,A,mask);
            var plan = new JointComputationPlan(Map.of("a",kind.type()),Map.of(),List.of(new JointComputationPlan.Output("out",kind.type(),remainder)));
            var assumptions = Set.of(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE,"a","","captured sign proof"));
            var request = new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(kind),ComputationOptimizer.SEMANTICS_REVISION,
                assumptions,SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
            var optimizer = new ComputationOptimizer();
            assertInstanceOf(VerificationResult.Verified.class,optimizer.verify(request,plan.withOutputs(List.of(masked)),CancellationToken.NONE));
            var optimized=assertInstanceOf(OptimizationResult.Candidate.class,optimizer.optimize(request,CancellationToken.NONE));
            assertTrue(optimized.cost().estimatedRuntimeImprovement());
            assertEquals(List.of(masked),optimized.plan().outputExpressions());
            var proposals = new JavaCandidateGenerator(request,new VerificationWork(request,CancellationToken.NONE)).generate(plan,32);
            assertTrue(proposals.proposals().stream().anyMatch(p -> plan.withExpression(p.expression()).outputExpressions().equals(List.of(masked))));
            var unknown = new OptimizationRequest(plan,request.sourceTrace(),request.selectedKinds(),request.semanticsRevision(),Set.of(),
                request.safetyProfile(),request.goal(),request.budget(),request.checkedPolicy());
            assertFalse(optimizer.verify(unknown,plan.withOutputs(List.of(masked)),CancellationToken.NONE) instanceof VerificationResult.Verified);
            for (long n : new long[]{0,1,7,8,9,Integer.MAX_VALUE}) {
                Object value; if(kind==NumericKind.INT) value=(int)n; else value=n;
                assertEquals(n%8,((Number)ComputationOptimizer.prepare(plan.withOutputs(List.of(masked))).execute(Map.of("a",value)).get("out")).longValue());
            }
        }
    }

    @Test void preparedRuntimeRejectsBigIntegerSubclassEvenForOutputOnlyPlans() {
        var request=request(List.of(A)); var plan = request.plan();
        var values = new HashMap<String,Object>(); for(String input:plan.inputs().keySet()) values.put(input,BigInteger.ONE);
        values.put("a",new BigInteger("1") {
            @Override public BigInteger add(BigInteger other) { throw new AssertionError("override invoked"); }
            @Override public BigInteger abs() { throw new AssertionError("assumption invoked override"); }
        });
        assertThrows(IllegalArgumentException.class,() -> ComputationOptimizer.prepare(plan).execute(values));
        assertThrows(IllegalArgumentException.class,() -> RuntimeChecks.validateAssumptions(request,values));
    }

    @Test void scalarVerificationHonorsCancellationAndExhaustion() {
        var request = request(List.of(mod(mul(A,B),Q)));
        var optimizer = new ComputationOptimizer();
        assertInstanceOf(VerificationResult.Cancelled.class,optimizer.verify(request,request.plan(),() -> true));
        var tiny = new OptimizationRequest(request.plan(),request.sourceTrace(),request.selectedKinds(),request.semanticsRevision(),request.assumptions(),
            request.safetyProfile(),request.goal(),new OptimizationBudget(1,1,1,1),request.checkedPolicy());
        assertInstanceOf(VerificationResult.BudgetExceeded.class,optimizer.verify(tiny,request.plan(),CancellationToken.NONE));
    }

    static OptimizationRequest request(List<Expr> expressions) {
        var inputs = new LinkedHashMap<String,de.regelsuche.search.program.ComputationBackend.Type>();
        var assumptions = new HashSet<SemanticAssumption>();
        for(String name:List.of("a","b","c","d","q")) {
            inputs.put(name,NumericKind.BIG_INTEGER.type());
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,name,"","exact value"));
            assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,name,"256","source bound"));
        }
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE,"q","","positive modulus"));
        var outputs = new ArrayList<JointComputationPlan.Output>();
        for(int i=0;i<expressions.size();i++) outputs.add(new JointComputationPlan.Output("out"+i,NumericKind.BIG_INTEGER.type(),expressions.get(i)));
        var plan = new JointComputationPlan(inputs,Map.of(),outputs);
        return new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(NumericKind.BIG_INTEGER),ComputationOptimizer.SEMANTICS_REVISION,
            assumptions,SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
    }
    private static long products(JointComputationPlan plan) { return ComputationOptimizer.prepare(plan).nodes().stream()
        .filter(n -> JavaExpressions.operationOf(n.expression()).orElse(null)==NumericOperation.MULTIPLY).count(); }
    static Expr op(NumericOperation op, Expr... args) { return JavaExpressions.operation(NumericKind.BIG_INTEGER,op,args); }
    static Expr mul(Expr a,Expr b) { return op(NumericOperation.MULTIPLY,a,b); }
    static Expr mod(Expr a,Expr q) { return op(NumericOperation.MOD,a,q); }
}
