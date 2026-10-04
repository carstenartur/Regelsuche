package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.VariableExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.NumberExpr;
import de.regelsuche.search.program.ComputationBackend;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecoverySafetyRegressionTest {
    @Test
    void primitiveLiteralCastsAreProvedExactlyAtJavaNarrowingBoundaries() {
        for (var kind:List.of(NumericKind.BYTE,NumericKind.SHORT,NumericKind.CHAR)) {
            for (int value:List.of(-65537,-32769,-129,-1,0,127,255,32768,65535,65536)) {
                Expr literal=switch(kind) {
                    case BYTE -> JavaExpressions.literal((byte)value);
                    case SHORT -> JavaExpressions.literal((short)value);
                    case CHAR -> JavaExpressions.literal((char)value);
                    default -> throw new AssertionError();
                };
                var source=new JointComputationPlan(Map.of(),Map.of(),List.of(new JointComputationPlan.Output("out",kind.type(),literal)));
                var target=source.withOutputs(List.of(JavaExpressions.cast(NumericKind.INT,kind,JavaExpressions.literal(value))));
                var request=OptimizationContractTest.request(source,SafetyProfile.PRESERVE_JAVA);
                assertInstanceOf(VerificationResult.Verified.class,new ComputationOptimizer().verify(request,target,CancellationToken.NONE));
                assertEquals(ComputationOptimizer.prepare(source).execute(Map.of()),ComputationOptimizer.prepare(target).execute(Map.of()));
            }
        }
        var source=OptimizationContractTest.plan(NumericKind.INT,JavaExpressions.literal(256));
        var narrowed=JavaExpressions.cast(NumericKind.BYTE,NumericKind.INT,
            JavaExpressions.cast(NumericKind.INT,NumericKind.BYTE,JavaExpressions.literal(256)));
        assertInstanceOf(VerificationResult.Refuted.class,new ComputationOptimizer().verify(
            OptimizationContractTest.request(source,SafetyProfile.PRESERVE_JAVA),source.withOutputs(List.of(narrowed)),CancellationToken.NONE));
    }

    @Test
    void candidateDiscardsAHostileBackendEvenWhenItsPreparedStructureIsIdentical() {
        var source = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(),
            List.of(new JointComputationPlan.Output("out", NumericKind.INT.type(),
                JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD,
                    new VariableExpr("x"), JavaExpressions.literal(0)))));
        var optimizer = new ComputationOptimizer();
        var request = OptimizationContractTest.request(source, SafetyProfile.PRESERVE_JAVA);
        var proof = assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, source, CancellationToken.NONE));
        var delegate = new JavaNumericBackend(source.inputs());
        ComputationBackend hostile = new ComputationBackend() {
            public Type literalType(NumberExpr literal) { return delegate.literalType(literal); }
            public Object literal(NumberExpr literal) { return delegate.literal(literal); }
            public Operation operation(Expr expression) { return delegate.operation(expression); }
            public Object apply(Operation operation, List<Object> arguments) { return 999; }
            public long leafStorage(Type type) { return delegate.leafStorage(type); }
        };
        var forged = source.prepare(hostile);
        var trusted = ComputationOptimizer.prepare(source);
        assertEquals(trusted.nodes(), forged.nodes());
        assertEquals(trusted.cost(), forged.cost());
        assertEquals(999, forged.execute(Map.of("x", 1)).get("out"));
        var candidate = new OptimizationResult.Candidate(source, forged, proof.evidence(), proof.obligations(),
            new OptimizationResult.CostAssessment(1, 1, 0, 0, trusted.cost(), trusted.cost(), false),
            OptimizationResult.SearchCompletion.EXHAUSTED_BOUNDED_SPACE, 1);
        assertEquals(1, candidate.prepared().execute(Map.of("x", 1)).get("out"));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
    }

    @Test
    void mixedPolynomialAndBitwiseIdentitiesRetainTheExecutedWidth() {
        var x = new VariableExpr("x");
        var plusZero = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, x, JavaExpressions.literal(0));
        var xorZero = JavaExpressions.operation(NumericKind.INT, NumericOperation.XOR, plusZero, JavaExpressions.literal(0));
        var plan = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(), List.of(
            new JointComputationPlan.Output("original", NumericKind.INT.type(), x),
            new JointComputationPlan.Output("added", NumericKind.INT.type(), plusZero),
            new JointComputationPlan.Output("result", NumericKind.INT.type(), xorZero)));
        var request = OptimizationContractTest.request(plan, SafetyProfile.PRESERVE_JAVA);
        var optimizer = new ComputationOptimizer();
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertEquals(Map.of("original", Integer.MIN_VALUE, "added", Integer.MIN_VALUE, "result", Integer.MIN_VALUE),
            candidate.prepared().execute(Map.of("x", Integer.MIN_VALUE)));
    }
    @Test
    void publicCandidateCannotReplaceThePreparedProgramAuthorizedByItsEvidence() {
        var source = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(),
            List.of(new JointComputationPlan.Output("out", NumericKind.INT.type(),
                JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD,
                    new VariableExpr("x"), JavaExpressions.literal(0)))));
        var request = new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source),
            Set.of(NumericKind.INT), ComputationOptimizer.SEMANTICS_REVISION, Set.of(),
            SafetyProfile.PRESERVE_JAVA, OptimizationGoal.LOWER_ESTIMATED_RUNTIME,
            OptimizationBudget.DEFAULT, CheckedPolicy.NONE);
        var optimizer = new ComputationOptimizer();
        var good = assertInstanceOf(OptimizationResult.Candidate.class,
            optimizer.optimize(request, CancellationToken.NONE));
        var wrong = source.withOutputs(List.of(JavaExpressions.literal(999)));
        assertThrows(IllegalArgumentException.class, () -> new OptimizationResult.Candidate(
            good.plan(), ComputationOptimizer.prepare(wrong), good.evidence(), good.obligations(),
            good.cost(), good.searchCompletion(), good.work()));
        assertEquals(1, good.prepared().execute(Map.of("x", 1)).get("out"));
    }
}
