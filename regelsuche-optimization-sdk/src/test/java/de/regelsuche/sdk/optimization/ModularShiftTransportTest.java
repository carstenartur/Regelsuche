package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModularShiftTransportTest {
    private static final Expr X = new VariableExpr("x"), M = new VariableExpr("m");
    private static final Expr E = new VariableExpr("e"), Z = new VariableExpr("z");

    @Test void anIntDistanceShiftRoundTripsWithExactValuesAndItsJavaSignature() {
        var backend = new JavaNumericBackend(Map.of("x", NumericKind.BIG_INTEGER.type()));
        for (int distance : new int[] {0, 1, 7, 4095}) {
            Expr shift = shift(X, distance);
            Expr transported = assertDoesNotThrow(() -> ModularBridge.toModular(shift));
            assertEquals(distance == 0 ? X : new BinaryExpr(X, BinaryOperator.MUL,
                    JavaExpressions.literal(BigInteger.ONE.shiftLeft(distance))), transported);
            Expr restored = ModularBridge.fromModular(transported);
            assertEquals(distance == 0 ? X : shift, restored);
            if (distance != 0) assertEquals(List.of(NumericKind.BIG_INTEGER.type(), NumericKind.INT.type()),
                    backend.operation(restored).arguments());
        }
    }

    @Test void modularGenerationKeepsUnrelatedOutputsAndAnIntDistanceInTheSignedBase() {
        Expr power = op(NumericOperation.MOD_POW, shift(X, 7), literal(2), M);
        Expr primitive = JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, Z, JavaExpressions.literal(9));
        Expr exactProduct = op(NumericOperation.MULTIPLY, X, literal(8));
        Expr negativeShift = shift(X, -1);
        var outputs = List.of(power, primitive, exactProduct, negativeShift);
        var bridge = new ModularBridge(assumptions(Map.of("x", NumericKind.BIG_INTEGER.type(),
                "m", NumericKind.BIG_INTEGER.type(), "z", NumericKind.INT.type())));

        var generated = bridge.generate(outputs, 16);

        assertFalse(generated.rewrites().isEmpty(), "shifted modular base must participate in existing generation");
        for (var rewrite : generated.rewrites()) {
            assertEquals(outputs.subList(1, outputs.size()), rewrite.outputs().subList(1, outputs.size()));
            assertTrue(bridge.verify(power, rewrite.outputs().getFirst()).accepted());
            var replacement = plan(rewrite.outputs());
            var prepared = ComputationOptimizer.prepare(replacement);
            assertTrue(prepared.nodes().stream().anyMatch(n -> n.expression().equals(shift(X, 7))));
            for (long x : new long[] {-257, -17, -1, 0, 1, 19, 257}) for (int modulus : new int[] {1, 2, 17, 101}) {
                var input = Map.<String, Object>of("x", BigInteger.valueOf(x), "m", BigInteger.valueOf(modulus), "z", Integer.MAX_VALUE);
                assertEquals(ComputationOptimizer.prepare(plan(outputs)).execute(input), prepared.execute(input));
            }
        }
        var source = plan(outputs);
        var request = request(source);
        var optimizer = new ComputationOptimizer();
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertEquals(outputs.subList(1, outputs.size()), candidate.plan().outputExpressions().subList(1, outputs.size()));
        assertEquals(request.sourceTrace(), candidate.obligations().originalTrace());
    }

    @Test void shiftedAffineExponentParticipatesInTheProductionSharedSearch() {
        Expr first = op(NumericOperation.MOD_POW, X, op(NumericOperation.ADD, E, literal(1)), M);
        Expr second = op(NumericOperation.MOD_POW, X, op(NumericOperation.ADD, shift(E, 1), literal(1)), M);
        var source = new JointComputationPlan(Map.of("x", NumericKind.BIG_INTEGER.type(),
                "e", NumericKind.BIG_INTEGER.type(), "m", NumericKind.BIG_INTEGER.type()), Map.of(), List.of(
                new JointComputationPlan.Output("first", NumericKind.BIG_INTEGER.type(), first),
                new JointComputationPlan.Output("second", NumericKind.BIG_INTEGER.type(), second)));
        var request = request(source);
        var optimizer = new ComputationOptimizer();

        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));

        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertEquals(request.sourceTrace(), candidate.obligations().originalTrace());
        assertEquals(1, candidate.prepared().nodes().stream().filter(n ->
                JavaExpressions.operationOf(n.expression()).orElse(null) == NumericOperation.MOD_POW).count());
        assertTrue(candidate.cost().estimatedRuntimeImprovement());
        assertTrue(candidate.derivation().isPresent());
        for (long x : new long[] {-257, -17, -1, 0, 1, 19, 257}) for (int exponent : new int[] {0, 1, 2, 5})
            for (int modulus : new int[] {1, 2, 17, 101}) {
                var inputs = Map.<String, Object>of("x", BigInteger.valueOf(x), "e", BigInteger.valueOf(exponent), "m", BigInteger.valueOf(modulus));
                assertEquals(ComputationOptimizer.prepare(source).execute(inputs), candidate.prepared().execute(inputs));
            }
    }

    @Test void generationPreservesExactArithmeticContextBesideTheModularRewrite() {
        Expr exactProduct = op(NumericOperation.MULTIPLY, X, literal(8));
        Expr power = op(NumericOperation.MOD_POW, shift(X, 3), literal(2), M);
        Expr sourceExpression = op(NumericOperation.ADD, power, exactProduct);
        var source = plan(List.of(sourceExpression));
        var request = request(source);

        var generated = new ModularBridge(request.assumptions()).generate(List.of(sourceExpression), 16);

        assertFalse(generated.rewrites().isEmpty());
        for (var rewrite : generated.rewrites()) assertEquals(exactProduct,
                JavaExpressions.operands(rewrite.outputs().getFirst()).get(1),
                "an unchanged exact product must retain its original Java operation");
        var optimizer = new ComputationOptimizer();
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertEquals(request.sourceTrace(), candidate.obligations().originalTrace());
        assertEquals(exactProduct, JavaExpressions.operands(candidate.plan().outputExpressions().getFirst()).get(1));
        for (long x : new long[] {-257, -1, 0, 1, 257}) for (int modulus : new int[] {1, 2, 17, 101}) {
            var input = Map.<String, Object>of("x", BigInteger.valueOf(x), "m", BigInteger.valueOf(modulus), "z", 0);
            assertEquals(ComputationOptimizer.prepare(source).execute(input), candidate.prepared().execute(input));
        }
    }

    @Test void zeroDistanceHasAnIndependentModularProofAndRetainsTheSourceShiftTrace() {
        Expr originalShift = shift(X, 0);
        Expr sourceExpression = op(NumericOperation.MOD, op(NumericOperation.MOD, originalShift, M), M);
        Expr reduced = op(NumericOperation.MOD, X, M);
        var source = plan(List.of(sourceExpression));
        var request = request(source);
        var optimizer = new ComputationOptimizer();

        assertTrue(new ModularBridge(request.assumptions()).verify(sourceExpression, reduced).accepted());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(request, source.withOutputs(List.of(reduced)), CancellationToken.NONE));
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        assertEquals(request.sourceTrace(), candidate.obligations().originalTrace());
        assertTrue(candidate.obligations().originalTrace().occurrences().stream().anyMatch(o -> o.expression().equals(originalShift)));
        assertFalse(new ModularBridge(request.assumptions()).verify(sourceExpression, X).accepted());
        var input = Map.<String, Object>of("x", BigInteger.valueOf(-129), "m", BigInteger.valueOf(17), "z", 0);
        assertEquals(ComputationOptimizer.prepare(source).execute(input), candidate.prepared().execute(input));
    }

    @Test void rewritesInsideShiftedModularBasesRetainTheOriginalIntDistances() {
        var optimizer = new ComputationOptimizer();
        for (int distance : new int[] {0, 3}) {
            Expr sourceExpression = shift(op(NumericOperation.MOD_POW, X, literal(2), M), distance);
            var source = plan(List.of(sourceExpression));
            var request = request(source);
            var generated = new ModularBridge(request.assumptions()).generate(List.of(sourceExpression), 16);
            assertFalse(generated.rewrites().isEmpty());
            for (var rewrite : generated.rewrites()) {
                Expr shiftedReplacement = rewrite.outputs().getFirst();
                assertEquals(NumericOperation.SHIFT_LEFT, JavaExpressions.operationOf(shiftedReplacement).orElseThrow());
                assertEquals(JavaExpressions.literal(distance), JavaExpressions.operands(shiftedReplacement).get(1));
                assertEquals(List.of(NumericKind.BIG_INTEGER.type(), NumericKind.INT.type()),
                        new JavaNumericBackend(source.inputs()).operation(shiftedReplacement).arguments());
                assertInstanceOf(VerificationResult.Verified.class,
                        optimizer.verify(request, source.withOutputs(rewrite.outputs()), CancellationToken.NONE));
            }
            var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
            assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
            assertEquals(request.sourceTrace(), candidate.obligations().originalTrace());
            assertEquals(JavaExpressions.literal(distance), JavaExpressions.operands(candidate.plan().outputExpressions().getFirst()).get(1));
            for (long x : new long[] {-257, -1, 0, 1, 257}) for (int modulus : new int[] {1, 2, 17, 101}) {
                var input = Map.<String, Object>of("x", BigInteger.valueOf(x), "m", BigInteger.valueOf(modulus), "z", 0);
                assertEquals(ComputationOptimizer.prepare(source).execute(input), candidate.prepared().execute(input));
            }
        }
    }

    @Test void negativeOversizedNonconstantAndNonIntDistancesFailClosed() {
        var invalidDistances = List.of(JavaExpressions.literal(-1), JavaExpressions.literal(Integer.MIN_VALUE),
                JavaExpressions.literal(4096), JavaExpressions.literal(Integer.MAX_VALUE), JavaExpressions.literal(1L),
                literal(1), new VariableExpr("distance"),
                JavaExpressions.operation(NumericKind.INT, NumericOperation.ADD, JavaExpressions.literal(1), JavaExpressions.literal(1)));
        var bridge = new ModularBridge(Set.of(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE, "m", "", "positive test modulus")));
        for (Expr distance : invalidDistances) {
            Expr unsupported = op(NumericOperation.SHIFT_LEFT, X, distance);
            Expr power = op(NumericOperation.MOD_POW, unsupported, literal(2), M);
            assertThrows(IllegalArgumentException.class, () -> ModularBridge.toModular(unsupported), distance.toString());
            assertTrue(bridge.generate(List.of(power), 16).rewrites().isEmpty(), distance.toString());
            assertThrows(IllegalArgumentException.class, () -> bridge.verify(power, power), distance.toString());
        }
    }

    @Test void neitherAnUnreducedResidueNorAnIncorrectShiftIsAProof() {
        Expr sourceExpression = op(NumericOperation.MOD, shift(X, 5), M);
        var source = plan(List.of(sourceExpression));
        var request = request(source);
        var bridge = new ModularBridge(request.assumptions());
        var optimizer = new ComputationOptimizer();
        for (Expr target : List.of(shift(X, 5), op(NumericOperation.MOD, shift(X, 4), M),
                op(NumericOperation.MOD, op(NumericOperation.MULTIPLY, X, literal(31)), M))) {
            assertFalse(bridge.verify(sourceExpression, target).accepted());
            assertFalse(optimizer.verify(request, source.withOutputs(List.of(target)), CancellationToken.NONE) instanceof VerificationResult.Verified);
        }
    }

    @Test void positiveModulusAndBothJavaMagnitudeGatesRemainRequired() {
        Expr sourceExpression = op(NumericOperation.MOD, shift(X, 1), M);
        Expr equivalent = op(NumericOperation.MOD, op(NumericOperation.MULTIPLY, X, literal(2)), M);
        var source = plan(List.of(sourceExpression));
        var normal = request(source);
        var optimizer = new ComputationOptimizer();
        assertInstanceOf(VerificationResult.Verified.class, optimizer.verify(normal, source.withOutputs(List.of(equivalent)), CancellationToken.NONE));
        var noPositive = new HashSet<>(normal.assumptions());
        noPositive.removeIf(a -> a.kind() == SemanticAssumption.Kind.POSITIVE);
        assertFalse(new ModularBridge(noPositive).verify(sourceExpression, equivalent).accepted());
        assertInstanceOf(VerificationResult.Unsupported.class, optimizer.verify(withAssumptions(normal, noPositive), source.withOutputs(List.of(equivalent)), CancellationToken.NONE));
        var noMagnitude = new HashSet<>(normal.assumptions());
        noMagnitude.removeIf(a -> a.kind() == SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND && a.subject().equals("x"));
        assertInstanceOf(VerificationResult.Unsupported.class, optimizer.verify(withAssumptions(normal, noMagnitude), source.withOutputs(List.of(equivalent)), CancellationToken.NONE));
        for (long bound : new long[] {BigIntegerBounds.MAX_BITS, BigIntegerBounds.MAX_BITS - 1}) {
            var oversized = new HashSet<>(normal.assumptions());
            oversized.removeIf(a -> a.kind() == SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND && a.subject().equals("x"));
            oversized.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, "x", Long.toString(bound), "source or candidate range boundary"));
            var rejected = assertInstanceOf(VerificationResult.Unsupported.class,
                    optimizer.verify(withAssumptions(normal, oversized), source.withOutputs(List.of(equivalent)), CancellationToken.NONE));
            assertEquals("BIG_INTEGER_SUPPORTED_RANGE_NOT_PROVED", rejected.diagnostic());
        }
    }

    @Test void verificationChargesTheFullSourceTransportWhenZeroShiftsDisappear() {
        Expr nested = X;
        int shifts = 40;
        for (int i = 0; i < shifts; i++) nested = shift(nested, 0);
        Expr source = op(NumericOperation.MOD, nested, M), target = op(NumericOperation.MOD, X, M);
        var proof = new ModularBridge(assumptions(plan(List.of(source)).inputs())).verify(source, target);
        assertTrue(proof.accepted());
        assertTrue(proof.work() >= 3L * shifts + 6, "transport work must include the erased source nodes and the target");
    }

    @Test void theProductionPathHonorsRequestBudgetAndCancellation() {
        Expr base = shift(X, 5);
        var source = plan(List.of(op(NumericOperation.MOD_POW, base, literal(2), M)));
        var target = source.withOutputs(List.of(op(NumericOperation.MOD_MULTIPLY, base, base, M)));
        var request = request(source);
        var optimizer = new ComputationOptimizer();
        assertInstanceOf(OptimizationResult.Cancelled.class, optimizer.optimize(request, () -> true));
        assertInstanceOf(VerificationResult.Cancelled.class, optimizer.verify(request, target, () -> true));
        var tiny = new OptimizationRequest(source, request.sourceTrace(), request.selectedKinds(), request.semanticsRevision(),
                request.assumptions(), request.safetyProfile(), request.goal(), new OptimizationBudget(1, 20_000, 64, 5000), request.checkedPolicy());
        assertInstanceOf(OptimizationResult.BudgetExceeded.class, optimizer.optimize(tiny, CancellationToken.NONE));
        assertInstanceOf(VerificationResult.BudgetExceeded.class, optimizer.verify(tiny, target, CancellationToken.NONE));
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        assertInstanceOf(OptimizationResult.Cancelled.class, optimizer.optimize(request, () -> polls.incrementAndGet() > 100));
    }

    private static OptimizationRequest withAssumptions(OptimizationRequest request, Set<SemanticAssumption> assumptions) {
        return new OptimizationRequest(request.plan(), request.sourceTrace(), request.selectedKinds(), request.semanticsRevision(), assumptions,
                request.safetyProfile(), request.goal(), request.budget(), request.checkedPolicy());
    }

    private static JointComputationPlan plan(List<Expr> expressions) {
        var outputs = new ArrayList<JointComputationPlan.Output>();
        for (int i = 0; i < expressions.size(); i++) {
            Expr expression = expressions.get(i);
            outputs.add(new JointComputationPlan.Output("out" + i, JavaExpressions.resultKind(expression).type(), expression));
        }
        return new JointComputationPlan(Map.of("x", NumericKind.BIG_INTEGER.type(), "m", NumericKind.BIG_INTEGER.type(),
                "z", NumericKind.INT.type()), Map.of(), outputs);
    }

    private static OptimizationRequest request(JointComputationPlan source) {
        return new OptimizationRequest(source, SourceEvaluationTrace.fromPlan(source), Set.of(NumericKind.BIG_INTEGER, NumericKind.INT),
                ComputationOptimizer.SEMANTICS_REVISION, assumptions(source.inputs()), SafetyProfile.PRESERVE_JAVA,
                OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1_000_000, 20_000, 64, 5000), CheckedPolicy.NONE);
    }

    private static Set<SemanticAssumption> assumptions(Map<String, de.regelsuche.search.program.ComputationBackend.Type> inputs) {
        var result = new HashSet<SemanticAssumption>();
        for (var input : inputs.entrySet()) if (input.getValue().equals(NumericKind.BIG_INTEGER.type())) {
            result.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_VALUE_SEMANTICS, input.getKey(), "", "captured test value"));
            result.add(new SemanticAssumption(SemanticAssumption.Kind.BIG_INTEGER_BIT_LENGTH_BOUND, input.getKey(), "64", "captured test magnitude"));
        }
        result.add(new SemanticAssumption(SemanticAssumption.Kind.POSITIVE, "m", "", "captured positive modulus"));
        if (inputs.containsKey("e")) result.add(new SemanticAssumption(SemanticAssumption.Kind.NON_NEGATIVE, "e", "", "captured exponent"));
        return result;
    }

    private static Expr shift(Expr base, int distance) { return op(NumericOperation.SHIFT_LEFT, base, JavaExpressions.literal(distance)); }
    private static Expr literal(long value) { return JavaExpressions.literal(BigInteger.valueOf(value)); }
    private static Expr op(NumericOperation operation, Expr... args) { return JavaExpressions.operation(NumericKind.BIG_INTEGER, operation, args); }
}
