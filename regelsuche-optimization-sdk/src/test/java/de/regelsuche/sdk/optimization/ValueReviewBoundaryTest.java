package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.sdk.optimization.ValueArithmeticTest.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ValueReviewBoundaryTest {
    @Test void irrelevantOutputsUseLinearDiscoveryWorkWithinDefaultAllowance() {
        var request=request(List.of(new VariableExpr("a")));
        var work=new VerificationWork(request,CancellationToken.NONE);
        List<Expr> outputs=new ArrayList<>();
        for(int i=0;i<1500;i++) outputs.add(new VariableExpr("primitive"+i));
        assertTrue(assertDoesNotThrow(() -> new BilinearCandidates(work).generate(outputs,32)).isEmpty());
        assertTrue(work.used()<=2L*outputs.size(),"irrelevant outputs must not be paired");
        assertThrows(VerificationWork.Stopped.class,() -> new BilinearCandidates(new VerificationWork(request,() -> true)).generate(outputs,32));
    }

    @Test void prefilterPreservesNonadjacentOutputIndicesAndModulus() {
        Expr a=new VariableExpr("a"),b=new VariableExpr("b"),c=new VariableExpr("c"),d=new VariableExpr("d"),q=new VariableExpr("q");
        for(boolean modular:List.of(false,true)) {
            var outputs=new ArrayList<Expr>(Collections.nCopies(30,a));
            Expr real=op(NumericOperation.SUBTRACT,mul(a,c),mul(b,d)),imaginary=op(NumericOperation.ADD,mul(a,d),mul(b,c));
            outputs.set(23,modular?mod(real,q):real); outputs.set(3,modular?mod(imaginary,q):imaginary);
            var request=request(outputs);
            var proposals=new BilinearCandidates(new VerificationWork(request,CancellationToken.NONE)).generate(outputs,1);
            assertEquals(1,proposals.size()); var changed=proposals.getFirst();
            for(int i=0;i<outputs.size();i++) if(i!=3) assertEquals(outputs.get(i),changed.get(i));
            assertNotEquals(outputs.get(3),changed.get(3));
            assertInstanceOf(VerificationResult.Verified.class,new ComputationOptimizer().verify(request,request.plan().withOutputs(changed),CancellationToken.NONE));
        }
    }

    @Test void assumptionChecksUseOneBoundedSnapshotForSizeAndUnitReceivers() {
        for(var kind:List.of(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,SemanticAssumption.Kind.MODULAR_UNIT_INPUT)) {
            var request=singleAssumption(kind);
            var changing=changingMap(Map.of("a",BigInteger.ONE,"q",BigInteger.valueOf(15)),Map.of("a",explosive(),"q",explosive()),1);
            assertDoesNotThrow(() -> RuntimeChecks.validateAssumptions(request,changing));
            assertThrows(IllegalArgumentException.class,() -> RuntimeChecks.validateAssumptions(request,
                changingMap(Map.of("a",explosive(),"q",BigInteger.valueOf(15)),Map.of(),1)));
        }
    }

    @Test void checkedEvaluationUsesTheSameCaptureAsItsGuards() {
        var request=singleAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND);
        var trace=SourceEvaluationTrace.fromPlan(request.plan());
        var obligations=new RuntimeObligations(RuntimeObligations.GuardKind.NONE,trace,trace,false,false,false,0,0);
        var changing=changingMap(Map.of("a",BigInteger.ONE,"q",BigInteger.valueOf(15)),Map.of("a",explosive(),"q",explosive()),1);
        assertEquals(BigInteger.ONE,RuntimeChecks.checked(request,request.plan(),obligations,changing).get("out"));
    }

    @Test void guardedFallbackReusesTheCapturedValueAfterAnAssumptionFailure() {
        Expr a=new VariableExpr("a"); var source=op(NumericOperation.ADD,a,JavaExpressions.literal(BigInteger.ZERO));
        var initial=request(List.of(source)); var assumptions=new HashSet<>(initial.assumptions());
        assumptions.removeIf(f -> f.kind()==SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND && f.subject().equals("a"));
        assumptions.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,"a","1","small capture"));
        var request=new OptimizationRequest(initial.plan(),initial.sourceTrace(),initial.selectedKinds(),initial.semanticsRevision(),assumptions,
            SafetyProfile.GUARDED_FALLBACK,initial.goal(),initial.budget(),initial.checkedPolicy());
        var optimizer=new ComputationOptimizer();
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,optimizer.optimize(request,CancellationToken.NONE));
        var values=new HashMap<String,Object>(); for(String input:request.plan().inputs().keySet()) values.put(input,BigInteger.ONE);
        values.put("a",BigInteger.TEN);
        assertEquals(BigInteger.TEN,optimizer.evaluateGuarded(request,candidate,changingMap(values,Map.of("a",BigInteger.valueOf(99)),1)).get("out0"));
    }

    private static OptimizationRequest singleAssumption(SemanticAssumption.Kind kind) {
        var plan=new JointComputationPlan(Map.of("a",NumericKind.BIG_INTEGER.type(),"q",NumericKind.BIG_INTEGER.type()),Map.of(),
            List.of(new JointComputationPlan.Output("out",NumericKind.BIG_INTEGER.type(),new VariableExpr("a"))));
        return new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(NumericKind.BIG_INTEGER),ComputationOptimizer.SEMANTICS_REVISION,
            Set.of(new SemanticAssumption(kind,"a",kind==SemanticAssumption.Kind.MODULAR_UNIT_INPUT?"q":"16","captured proof")),
            SafetyProfile.PRESERVE_JAVA,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
    }
    private static BigInteger explosive() { return new BigInteger("1") {
        @Override public BigInteger abs() { throw new AssertionError("unguarded abs receiver"); }
        @Override public BigInteger gcd(BigInteger other) { throw new AssertionError("unguarded gcd receiver"); }
        @Override public int signum() { throw new AssertionError("unguarded modulus receiver"); }
    }; }
    private static Map<String,Object> changingMap(Map<String,Object> first,Map<String,Object> later,int safeReads) {
        return new AbstractMap<>() {
            private final Map<Object,Integer> reads=new HashMap<>();
            @Override public Object get(Object key) { return reads.merge(key,1,Integer::sum)<=safeReads ? first.get(key) : later.getOrDefault(key,first.get(key)); }
            @Override public Set<Entry<String,Object>> entrySet() { throw new AssertionError("must snapshot only declared input keys"); }
        };
    }
}
