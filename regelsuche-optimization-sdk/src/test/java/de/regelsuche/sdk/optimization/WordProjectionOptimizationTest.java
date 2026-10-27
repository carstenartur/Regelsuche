package de.regelsuche.sdk.optimization;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.Expr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.search.program.JointComputationPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WordProjectionOptimizationTest {
    private static final Expr X = new VariableExpr("x");
    private final ComputationOptimizer optimizer = new ComputationOptimizer();

    @Test void reconstructsWordsFromConstantShiftAndMaskForEveryJavaDistance() {
        for (var kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            for (int distance : new int[] {-1, 0, 1, 16, 31, 32, 33, 63, 64, 65}) {
                Expr source = reconstruction(kind, X, distance, false);
                assertInstanceOf(VerificationResult.Verified.class,
                    optimizer.verify(request(kind, source), plan(kind, X), CancellationToken.NONE),
                    kind + " shift " + distance);
            }
        }
    }

    @Test void reconstructsLongFromItsNarrowedLowWordAndCarry() {
        Expr source = reconstruction(NumericKind.LONG, X, 32, true);
        assertInstanceOf(VerificationResult.Verified.class,
            optimizer.verify(request(NumericKind.LONG, source), plan(NumericKind.LONG, X), CancellationToken.NONE));
        var execution = ComputationOptimizer.prepare(plan(NumericKind.LONG, source));
        for (long value : new long[] {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0x12345678ffffffffL})
            assertEquals(value, execution.execute(Map.of("x", value)).get("out"));
    }

    @Test void optimizerFindsAndReverifiesTheGenericWordReconstruction() {
        for (var kind : List.of(NumericKind.INT, NumericKind.LONG)) {
            var request = request(kind, reconstruction(kind, X, kind.bits() / 2, false));
            var result = optimizer.optimize(request, CancellationToken.NONE);
            var candidate = assertInstanceOf(OptimizationResult.Candidate.class, result, result.toString());
            assertEquals(List.of(X), candidate.plan().outputExpressions());
            assertTrue(candidate.cost().candidateScore() < candidate.cost().sourceScore());
            assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
        }
    }

    @Test void outputBitsDoNotShareOneBooleanVariableAfterShifting() {
        Expr shifted = op(NumericKind.LONG, NumericOperation.SHIFT_LEFT, X, JavaExpressions.literal(1));
        assertFalse(optimizer.verify(request(NumericKind.LONG, shifted), plan(NumericKind.LONG, X), CancellationToken.NONE)
            instanceof VerificationResult.Verified);
        assertEquals(2L, ComputationOptimizer.prepare(plan(NumericKind.LONG, shifted)).execute(Map.of("x", 1L)).get("out"));
    }

    @Test void signExtensionNeedsTheLowWordMask() {
        var kind = NumericKind.LONG;
        Expr high = op(kind, NumericOperation.SHIFT_LEFT,
            op(kind, NumericOperation.UNSIGNED_SHIFT_RIGHT, X, JavaExpressions.literal(32)), JavaExpressions.literal(32));
        Expr low = JavaExpressions.cast(NumericKind.INT, kind, JavaExpressions.cast(kind, NumericKind.INT, X));
        Expr wrong = op(kind, NumericOperation.OR, high, low);
        assertFalse(optimizer.verify(request(kind, wrong), plan(kind, X), CancellationToken.NONE) instanceof VerificationResult.Verified);
        assertEquals(-1L, ComputationOptimizer.prepare(plan(kind, wrong)).execute(Map.of("x", 0x12345678ffffffffL)).get("out"));
    }

    @Test void signedAndUnsignedCarriesRemainDifferent() {
        var kind = NumericKind.LONG;
        Expr signed = op(kind, NumericOperation.SHIFT_RIGHT, X, JavaExpressions.literal(32));
        Expr unsigned = op(kind, NumericOperation.UNSIGNED_SHIFT_RIGHT, X, JavaExpressions.literal(32));
        assertFalse(optimizer.verify(request(kind, signed), plan(kind, unsigned), CancellationToken.NONE) instanceof VerificationResult.Verified);
    }

    @Test void charWideningZeroExtendsAndShortWideningSignExtends() {
        Expr charValue = JavaExpressions.cast(NumericKind.CHAR, NumericKind.INT,
            JavaExpressions.cast(NumericKind.INT, NumericKind.CHAR, X));
        Expr masked = op(NumericKind.INT, NumericOperation.AND, X, JavaExpressions.literal(65535));
        assertInstanceOf(VerificationResult.Verified.class,
            optimizer.verify(request(NumericKind.INT, charValue), plan(NumericKind.INT, masked), CancellationToken.NONE));
        Expr shortValue = JavaExpressions.cast(NumericKind.SHORT, NumericKind.INT,
            JavaExpressions.cast(NumericKind.INT, NumericKind.SHORT, X));
        assertFalse(optimizer.verify(request(NumericKind.INT, shortValue), plan(NumericKind.INT, masked), CancellationToken.NONE)
            instanceof VerificationResult.Verified);
    }

    @Test void projectionRetainsCancellationAndProofBudget() {
        var request = request(NumericKind.LONG, reconstruction(NumericKind.LONG, X, 32, true));
        assertInstanceOf(VerificationResult.Cancelled.class,
            optimizer.verify(request, plan(NumericKind.LONG, X), () -> true));
        var tiny = new OptimizationRequest(request.plan(), request.sourceTrace(), request.selectedKinds(), request.semanticsRevision(),
            request.assumptions(), request.safetyProfile(), request.goal(), new OptimizationBudget(1, 100, 8, 5000), request.checkedPolicy());
        assertInstanceOf(VerificationResult.BudgetExceeded.class,
            optimizer.verify(tiny, plan(NumericKind.LONG, X), CancellationToken.NONE));
    }

    @Test void projectionStopsInsideItsOwnWorkAndBitVariableBounds() {
        Expr source = reconstruction(NumericKind.LONG, X, 32, true);
        var request = request(NumericKind.LONG, source);
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        var complete = new VerificationWork(request, () -> { polls.incrementAndGet(); return false; });
        assertTrue(new WordBitProjection(complete).equivalent(source, X, NumericKind.LONG));
        assertTrue(complete.used() > 100);
        var limited = new OptimizationRequest(request.plan(), request.sourceTrace(), request.selectedKinds(), request.semanticsRevision(),
            request.assumptions(), request.safetyProfile(), request.goal(),
            new OptimizationBudget(complete.used() - 1, 100, 8, 5000), request.checkedPolicy());
        var work = new VerificationWork(limited, CancellationToken.NONE);
        assertFalse(assertThrows(VerificationWork.Stopped.class,
            () -> new WordBitProjection(work).equivalent(source, X, NumericKind.LONG)).cancelled);
        assertTrue(work.used() > complete.used() / 2);
        int threshold = polls.get() / 2;
        polls.set(0);
        var cancelled = new VerificationWork(request, () -> polls.incrementAndGet() > threshold);
        assertTrue(assertThrows(VerificationWork.Stopped.class,
            () -> new WordBitProjection(cancelled).equivalent(source, X, NumericKind.LONG)).cancelled);
        assertTrue(cancelled.used() > 0);
        Expr wide = X;
        for (int shift = 1; shift <= 8; shift++) wide = op(NumericKind.LONG, NumericOperation.OR, wide,
            op(NumericKind.LONG, NumericOperation.UNSIGNED_SHIFT_RIGHT, X, JavaExpressions.literal(shift)));
        assertFalse(new WordBitProjection(new VerificationWork(request, CancellationToken.NONE)).equivalent(wide, wide, NumericKind.LONG),
            "Nine independently shifted source bits exceed the local truth-table bound");
    }

    @Test void reconstructionHandlesSignedShiftsReorderedXorAndTypedCastChains() {
        Expr high = op(NumericKind.LONG, NumericOperation.SHIFT_LEFT,
            op(NumericKind.LONG, NumericOperation.SHIFT_RIGHT, X, JavaExpressions.literal(32L)), JavaExpressions.literal(96L));
        Expr narrow = JavaExpressions.cast(NumericKind.INT, NumericKind.LONG, JavaExpressions.cast(NumericKind.LONG, NumericKind.INT, X));
        Expr source = op(NumericKind.LONG, NumericOperation.XOR,
            op(NumericKind.LONG, NumericOperation.AND, JavaExpressions.literal(0xffffffffL), narrow), high);
        var request = request(NumericKind.LONG, source);
        var candidate = assertInstanceOf(OptimizationResult.Candidate.class, optimizer.optimize(request, CancellationToken.NONE));
        assertEquals(List.of(X), candidate.plan().outputExpressions());
        assertInstanceOf(VerificationResult.Verified.class, optimizer.reverify(request, candidate, CancellationToken.NONE));
    }

    @Test void wrongMasksAndLostLowBitsCannotReconstructAWord() {
        Expr high = op(NumericKind.LONG, NumericOperation.SHIFT_LEFT,
            op(NumericKind.LONG, NumericOperation.UNSIGNED_SHIFT_RIGHT, X, JavaExpressions.literal(32)), JavaExpressions.literal(32));
        Expr narrow = JavaExpressions.cast(NumericKind.SHORT, NumericKind.LONG, JavaExpressions.cast(NumericKind.LONG, NumericKind.SHORT, X));
        for (Expr low : List.of(op(NumericKind.LONG, NumericOperation.AND, X, JavaExpressions.literal(0xffffL)),
                op(NumericKind.LONG, NumericOperation.AND, narrow, JavaExpressions.literal(0xffffffffL)))) {
            Expr source = op(NumericKind.LONG, NumericOperation.OR, high, low);
            var request = request(NumericKind.LONG, source);
            assertFalse(optimizer.verify(request, plan(NumericKind.LONG, X), CancellationToken.NONE) instanceof VerificationResult.Verified);
            var result = optimizer.optimize(request, CancellationToken.NONE);
            if (result instanceof OptimizationResult.Candidate candidate) assertNotEquals(List.of(X), candidate.plan().outputExpressions());
        }
    }

    private static Expr reconstruction(NumericKind kind, Expr value, int distance, boolean narrow) {
        int shift = distance & (kind.bits() - 1);
        long mask = shift == 0 ? 0 : -1L >>> (64 - shift);
        Expr high = op(kind, NumericOperation.SHIFT_LEFT,
            op(kind, NumericOperation.UNSIGNED_SHIFT_RIGHT, value, JavaExpressions.literal(distance)), JavaExpressions.literal(distance));
        Expr low = narrow ? JavaExpressions.cast(NumericKind.INT, NumericKind.LONG,
            JavaExpressions.cast(NumericKind.LONG, NumericKind.INT, value)) : value;
        Expr maskLiteral = kind == NumericKind.LONG ? JavaExpressions.literal(mask) : JavaExpressions.literal((int) mask);
        return op(kind, NumericOperation.OR, high, op(kind, NumericOperation.AND, low, maskLiteral));
    }

    private static Expr op(NumericKind kind, NumericOperation operation, Expr... operands) {
        return JavaExpressions.operation(kind, operation, operands);
    }
    private static JointComputationPlan plan(NumericKind kind, Expr value) {
        return new JointComputationPlan(Map.of("x", kind.type()), Map.of(),
            List.of(new JointComputationPlan.Output("out", kind.type(), value)));
    }
    private static OptimizationRequest request(NumericKind kind, Expr value) {
        var plan = plan(kind, value);
        return new OptimizationRequest(plan, SourceEvaluationTrace.fromPlan(plan), Set.of(NumericKind.values()),
            ComputationOptimizer.SEMANTICS_REVISION, Set.of(), SafetyProfile.PRESERVE_JAVA,
            OptimizationGoal.LOWER_ESTIMATED_RUNTIME, new OptimizationBudget(1_000_000, 20_000, 64, 5000), CheckedPolicy.NONE);
    }
}
