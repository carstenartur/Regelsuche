package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuardedBigIntegerCostTest {
    @Test void guardedExecutionSharesThePreparedReplacementAcrossOutputs() {
        var kind = NumericKind.BIG_INTEGER;
        var x = new VariableExpr("x");
        var sum = JavaExpressions.operation(kind, NumericOperation.ADD, x, JavaExpressions.literal(BigInteger.ONE));
        var redundant = JavaExpressions.operation(kind, NumericOperation.ADD, sum, JavaExpressions.literal(BigInteger.ZERO));
        var sourceTrace = new SourceEvaluationTrace(List.of(
            new SourceEvaluationTrace.Occurrence("sum", sum, kind, kind),
            new SourceEvaluationTrace.Occurrence("redundant", redundant, kind, kind)));
        var assumptions = Set.of(
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "captured receiver"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", "64", "captured bound"));
        var optimizer = new ComputationOptimizer();
        var reads = new java.util.ArrayList<Integer>();
        for (int outputCount : List.of(1, 3)) {
            var outputs = new java.util.ArrayList<JointComputationPlan.Output>();
            outputs.add(new JointComputationPlan.Output("out0", kind.type(), redundant));
            for (int i = 1; i < outputCount; i++) outputs.add(new JointComputationPlan.Output("out" + i, kind.type(), sum));
            var plan = new JointComputationPlan(Map.of("x", kind.type()), Map.of(), outputs);
            var request = new OptimizationRequest(plan, sourceTrace, Set.of(kind), ComputationOptimizer.SEMANTICS_REVISION,
                assumptions, SafetyProfile.GUARDED_FALLBACK, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
                OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
            var candidate = assertInstanceOf(OptimizationResult.Candidate.class,
                optimizer.optimize(request, CancellationToken.NONE));
            assertEquals(8, candidate.cost().sourceCost().operationWork());
            assertEquals(4, candidate.cost().candidateCost().operationWork());
            assertEquals(0, candidate.obligations().estimatedCheckWork());
            assertTrue(candidate.cost().estimatedRuntimeImprovement());
            var inputs = new CountingInputs(Map.of("x", BigInteger.TEN));
            var result = optimizer.evaluateGuarded(request, candidate, inputs);
            assertEquals(outputCount, result.size());
            assertTrue(result.values().stream().allMatch(BigInteger.valueOf(11)::equals));
            reads.add(inputs.reads);
        }
        assertEquals(reads.getFirst(), reads.getLast(), "Shared output arithmetic must execute once, as costed");
    }

    @Test void guardedExecutionDoesNotReadEliminatedOriginalOperations() {
        var kind = NumericKind.BIG_INTEGER;
        var x = new VariableExpr("x");
        var one = JavaExpressions.literal(BigInteger.ONE);
        var shortSource = JavaExpressions.operation(kind, NumericOperation.SUBTRACT,
            JavaExpressions.operation(kind, NumericOperation.ADD, x, one), one);
        var repeatedSource = JavaExpressions.operation(kind, NumericOperation.ADD,
            JavaExpressions.operation(kind, NumericOperation.SUBTRACT, x, x), x);
        var assumptions = Set.of(
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "x", "", "captured receiver"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", "64", "captured bound"));
        var optimizer = new ComputationOptimizer();
        var reads = new java.util.ArrayList<Integer>();
        for (var source : List.of(shortSource, repeatedSource)) {
            var plan = new JointComputationPlan(Map.of("x", kind.type()), Map.of(),
                List.of(new JointComputationPlan.Output("out", kind.type(), source)));
            var request = guardedRequest(plan, assumptions);
            var candidate = assertInstanceOf(OptimizationResult.Candidate.class,
                optimizer.optimize(request, CancellationToken.NONE));
            assertEquals(List.of(x), candidate.plan().outputExpressions());
            var inputs = new CountingInputs(Map.of("x", BigInteger.TEN));
            assertEquals(BigInteger.TEN, optimizer.evaluateGuarded(request, candidate, inputs).get("out"));
            reads.add(inputs.reads);
        }
        // Both authorized replacements and input contracts are identical. Extra
        // variable occurrences in eliminated arithmetic must cause no runtime reads.
        assertEquals(reads.getFirst(), reads.getLast());
    }

    @Test void capturedIntExponentDoesNotIntroduceAPrimitiveRangeGate() {
        var kind = NumericKind.BIG_INTEGER;
        var power = JavaExpressions.operation(kind, NumericOperation.POW,
            new VariableExpr("a"), new VariableExpr("exponent"));
        var plan = new JointComputationPlan(Map.of("a", kind.type(), "exponent", NumericKind.INT.type()), Map.of(),
            List.of(new JointComputationPlan.Output("out", kind.type(),
                JavaExpressions.operation(kind, NumericOperation.SUBTRACT, power, power))));
        var assumptions = Set.of(
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, "a", "", "captured receiver"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "a", "64", "captured bound"),
            new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE_UPPER_BOUND, "exponent", "100", "captured scalar"));
        var request = guardedRequest(plan, assumptions);
        var optimizer = new ComputationOptimizer();
        var result = optimizer.optimize(request, CancellationToken.NONE);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
        assertEquals(RuntimeObligations.GuardKind.NONE, candidate.obligations().guard());
        assertFalse(candidate.obligations().checkIntegralRange());
        assertEquals(0, candidate.obligations().estimatedCheckWork());
        assertEquals(0, candidate.obligations().fallbackOperationCount());
        assertEquals(BigInteger.ZERO, optimizer.evaluateGuarded(request, candidate,
            Map.of("a", BigInteger.TWO, "exponent", 3)).get("out"));
    }

    @Test void noPrimitiveGateMustNotChargeASecondOriginalBigIntegerComputation() {
        var kind=NumericKind.BIG_INTEGER;
        var x=new VariableExpr("x");
        var one=JavaExpressions.literal(BigInteger.ONE);
        var sum=JavaExpressions.operation(kind,NumericOperation.ADD,x,one);
        var result=JavaExpressions.operation(kind,NumericOperation.SUBTRACT,sum,one);
        var plan=new JointComputationPlan(Map.of("x",kind.type()),Map.of(),List.of(new JointComputationPlan.Output("out",kind.type(),result)));
        var assumptions=Set.of(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS,"x","","host receiver contract"),
            new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND,"x","64","host magnitude contract"));
        var request=new OptimizationRequest(plan,SourceEvaluationTrace.fromPlan(plan),Set.of(kind),ComputationOptimizer.SEMANTICS_REVISION,assumptions,
            SafetyProfile.GUARDED_FALLBACK,OptimizationGoal.LOWER_ESTIMATED_RUNTIME,OptimizationBudget.DEFAULT,CheckedPolicy.NONE);
        var resultCandidate=new ComputationOptimizer().optimize(request,CancellationToken.NONE);
        var candidate=assertInstanceOf(OptimizationResult.Candidate.class,resultCandidate,resultCandidate.toString());
        assertEquals(RuntimeObligations.GuardKind.NONE,candidate.obligations().guard());
        assertEquals(0,candidate.obligations().estimatedCheckWork());
        assertEquals(0,candidate.obligations().fallbackOperationCount());
        assertEquals(BigInteger.TEN,candidate.prepared().execute(Map.of("x",BigInteger.TEN)).get("out"));
    }

    private static OptimizationRequest guardedRequest(JointComputationPlan plan, Set<SemanticAssumption> assumptions) {
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.BIG_INTEGER),
            ComputationOptimizer.SEMANTICS_REVISION, assumptions, SafetyProfile.GUARDED_FALLBACK,
            OptimizationGoal.LOWER_ESTIMATED_RUNTIME, OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
    }

    private static final class CountingInputs extends AbstractMap<String,Object> {
        private final Map<String,Object> values;
        private int reads;
        CountingInputs(Map<String,Object> values) { this.values = values; }
        @Override public Object get(Object name) { reads++; return values.get(name); }
        @Override public Set<Entry<String,Object>> entrySet() { return values.entrySet(); }
    }
}
