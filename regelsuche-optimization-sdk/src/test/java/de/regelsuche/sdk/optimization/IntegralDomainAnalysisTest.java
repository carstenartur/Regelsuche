package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntegralDomainAnalysisTest {
    private static final Expr X = new VariableExpr("x");

    @Test void distinguishesOriginalAndWidenedExactDomains() {
        Expr twice = op(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2));
        var source = plan(NumericKind.INT, op(NumericKind.INT, NumericOperation.DIVIDE, twice, JavaExpressions.literal(2)));
        var result = assertInstanceOf(IntegralDomainAnalysis.Verified.class,
                IntegralDomainAnalysis.compare(source, SourceEvaluationTrace.fromPlan(source), source.withOutputs(List.of(X))));
        assertEquals("[-1073741824, 1073741823]", result.source().inputRange().toString());
        assertEquals("[-2147483648, 2147483647]", result.candidate().inputRange().toString());
        assertEquals(BigInteger.ONE, result.source().outputs().getFirst().coefficient());
        assertEquals(BigInteger.ZERO, result.source().outputs().getFirst().constant());
    }

    @Test void longRangesUseExactIntegerBounds() {
        var source = plan(NumericKind.LONG, op(NumericKind.LONG, NumericOperation.MULTIPLY, X, JavaExpressions.literal(3L)));
        assertEquals("[-3074457345618258602, 3074457345618258602]", domain(source).inputRange().toString());
    }

    @Test void negativeCoefficientUsesSignedFloorAndCeiling() {
        var source = plan(NumericKind.INT, op(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(-3)));
        assertEquals("[-715827882, 715827882]", domain(source).inputRange().toString());
        var negate = plan(NumericKind.INT, op(NumericKind.INT, NumericOperation.NEGATE, X));
        assertEquals("[-2147483647, 2147483647]", domain(negate).inputRange().toString());
    }

    @Test void constrainsNarrowingAndEveryNestedIntermediateEvenWithEmptyTrace() {
        Expr doubled = op(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2));
        Expr restored = op(NumericKind.INT, NumericOperation.DIVIDE, doubled, JavaExpressions.literal(2));
        var result = assertInstanceOf(IntegralDomainAnalysis.Domain.class,
                IntegralDomainAnalysis.analyze(plan(NumericKind.INT, restored), new SourceEvaluationTrace(List.of())));
        assertEquals("[-1073741824, 1073741823]", result.inputRange().toString());
        var narrow = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", NumericKind.BYTE.type(),
                        JavaExpressions.cast(NumericKind.INT, NumericKind.BYTE, X))));
        assertEquals("[-128, 127]", domain(narrow).inputRange().toString());
    }

    @Test void originalTraceRetainsDeadOverflowConstraints() {
        var source = plan(NumericKind.INT, X);
        Expr dead = op(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(10));
        var trace = new SourceEvaluationTrace(List.of(new SourceEvaluationTrace.Occurrence("dead", dead, NumericKind.INT, NumericKind.INT)));
        var result = assertInstanceOf(IntegralDomainAnalysis.Verified.class,
                IntegralDomainAnalysis.compare(source, trace, source));
        assertEquals("[-2147483648, 2147483637]", result.source().inputRange().toString());
        assertEquals("[-2147483648, 2147483647]", result.candidate().inputRange().toString());
    }

    @Test void divisionMustBeExactAndCannotHaveZeroDivisor() {
        for (int divisor : new int[]{0, 2}) {
            var source = plan(NumericKind.INT, op(NumericKind.INT, NumericOperation.DIVIDE, X, JavaExpressions.literal(divisor)));
            assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                    IntegralDomainAnalysis.analyze(source, SourceEvaluationTrace.fromPlan(source)));
        }
        Expr numerator = op(NumericKind.INT, NumericOperation.ADD,
                op(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(6)), JavaExpressions.literal(4));
        var source = plan(NumericKind.INT, op(NumericKind.INT, NumericOperation.DIVIDE, numerator, JavaExpressions.literal(-2)));
        assertEquals(BigInteger.valueOf(-3), domain(source).outputs().getFirst().coefficient());
        assertEquals(BigInteger.valueOf(-2), domain(source).outputs().getFirst().constant());
    }

    @Test void multipleOutputsShareOneDomainAndMustKeepBindingsAndValues() {
        Expr next = op(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(1));
        var source = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of(), List.of(
                new JointComputationPlan.Output("a", NumericKind.INT.type(), X),
                new JointComputationPlan.Output("b", NumericKind.INT.type(), next)));
        assertEquals("[-2147483648, 2147483646]", domain(source).inputRange().toString());
        assertInstanceOf(IntegralDomainAnalysis.Verified.class,
                IntegralDomainAnalysis.compare(source, SourceEvaluationTrace.fromPlan(source), source));
        assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.compare(source, SourceEvaluationTrace.fromPlan(source), source.withOutputs(List.of(next, X))));
        var renamed = new JointComputationPlan(source.inputs(), Map.of(), List.of(
                new JointComputationPlan.Output("changed", NumericKind.INT.type(), X), source.outputs().get(1)));
        assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.compare(source, SourceEvaluationTrace.fromPlan(source), renamed));
    }

    @Test void candidateCannotReduceOriginalDomainOrChangeInputType() {
        var source = plan(NumericKind.INT, X);
        var narrow = plan(NumericKind.INT, op(NumericKind.INT, NumericOperation.DIVIDE,
                op(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2)), JavaExpressions.literal(2)));
        assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.compare(source, SourceEvaluationTrace.fromPlan(source), narrow));
        assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.compare(source, SourceEvaluationTrace.fromPlan(source), plan(NumericKind.LONG, X)));
    }

    @Test void charRangeAndResolvedDefinitionsRemainPortable() {
        assertEquals("[0, 65535]", domain(plan(NumericKind.CHAR, X)).inputRange().toString());
        Expr twice = op(NumericKind.INT, NumericOperation.MULTIPLY, X, JavaExpressions.literal(2));
        var source = new JointComputationPlan(Map.of("x", NumericKind.INT.type()), Map.of("twice", twice), List.of(
                new JointComputationPlan.Output("result", NumericKind.INT.type(),
                        op(NumericKind.INT, NumericOperation.DIVIDE, new VariableExpr("twice"), JavaExpressions.literal(2)))));
        assertEquals("[-1073741824, 1073741823]", domain(source).inputRange().toString());
    }

    @Test void nonlinearBitwiseAndIllTypedExpressionsAreExplicitlyUnsupported() {
        for (Expr expression : List.of(op(NumericKind.INT, NumericOperation.MULTIPLY, X, X),
                op(NumericKind.INT, NumericOperation.AND, X, JavaExpressions.literal(255)),
                op(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(1L)),
                JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, X))) {
            assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                    IntegralDomainAnalysis.analyze(plan(NumericKind.INT, expression), new SourceEvaluationTrace(List.of())));
        }
        var twoInputs = new JointComputationPlan(Map.of("x", NumericKind.INT.type(), "y", NumericKind.INT.type()),
                Map.of(), plan(NumericKind.INT, X).outputs());
        assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.analyze(twoInputs, new SourceEvaluationTrace(List.of())));
    }

    @Test void boundedAnalysisHonorsWorkAndCancellation() {
        var source = plan(NumericKind.INT, op(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(1)));
        var trace = SourceEvaluationTrace.fromPlan(source);
        var exhausted = assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.analyze(source, trace, 1, CancellationToken.NONE));
        assertEquals("DOMAIN_WORK_BOUND", exhausted.diagnostic());
        var cancelled = assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.analyze(source, trace, 1000, () -> true));
        assertEquals("CANCELLED", cancelled.diagnostic());
        assertInstanceOf(IntegralDomainAnalysis.Domain.class,
                IntegralDomainAnalysis.analyze(source, trace, 500, CancellationToken.NONE), "Small DAGs pay actual resolver work");
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        var duringResolution = assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.analyze(source, trace, 500, () -> polls.incrementAndGet() > 3));
        assertEquals("CANCELLED", duringResolution.diagnostic());
    }

    @Test void cachedDeadTraceRetainsTheStructuralDepthBound() {
        Expr deep = X;
        var trace = new ArrayList<SourceEvaluationTrace.Occurrence>();
        for (int index = 0; index < 150; index++) {
            deep = op(NumericKind.INT, NumericOperation.NEGATE, deep);
            trace.add(new SourceEvaluationTrace.Occurrence("step" + index, deep, NumericKind.INT, NumericKind.INT));
        }
        assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.analyze(plan(NumericKind.INT, X), new SourceEvaluationTrace(trace)));
    }

    @Test void malformedEncodedLiteralIsAnUnsupportedResult() {
        Expr invalid = new FunctionExpr("j_int_literal", List.of(JavaExpressions.literal(BigInteger.ONE.shiftLeft(31))));
        var result = assertDoesNotThrow(() -> IntegralDomainAnalysis.analyze(plan(NumericKind.INT, invalid),
                new SourceEvaluationTrace(List.of())));
        assertInstanceOf(IntegralDomainAnalysis.Unsupported.class, result);
    }

    @Test void exhaustedWorkStopsBeforeGraphResolution() {
        Expr deep = X;
        for (int index = 0; index < 150; index++) deep = op(NumericKind.INT, NumericOperation.NEGATE, deep);
        var result = assertInstanceOf(IntegralDomainAnalysis.Unsupported.class,
                IntegralDomainAnalysis.analyze(plan(NumericKind.INT, deep), new SourceEvaluationTrace(List.of()),
                        1, CancellationToken.NONE));
        assertEquals("DOMAIN_WORK_BOUND", result.diagnostic());
    }

    @Test void declaredAndEvaluatedTraceRangesBothConstrainTheDomain() {
        Expr value = op(NumericKind.INT, NumericOperation.ADD, X, JavaExpressions.literal(0));
        var trace = new SourceEvaluationTrace(List.of(
                new SourceEvaluationTrace.Occurrence("narrowed", value, NumericKind.BYTE, NumericKind.INT)));
        var result = assertInstanceOf(IntegralDomainAnalysis.Domain.class,
                IntegralDomainAnalysis.analyze(plan(NumericKind.INT, X), trace));
        assertEquals("[-128, 127]", result.inputRange().toString());
    }

    private static IntegralDomainAnalysis.Domain domain(JointComputationPlan plan) {
        return assertInstanceOf(IntegralDomainAnalysis.Domain.class,
                IntegralDomainAnalysis.analyze(plan, SourceEvaluationTrace.fromPlan(plan)));
    }
    private static Expr op(NumericKind kind, NumericOperation operation, Expr... operands) {
        return JavaExpressions.operation(kind, operation, operands);
    }
    private static JointComputationPlan plan(NumericKind kind, Expr expression) {
        return new JointComputationPlan(Map.of("x", kind.type()), Map.of(),
                List.of(new JointComputationPlan.Output("result", kind.type(), expression)));
    }
}
