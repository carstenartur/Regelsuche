package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.scalar.ExactRational;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;

class CheckedSchemaLiteralOwnershipTest {
    private static final class Limit extends IllegalArgumentException {}

    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        final Limit primary = new Limit();
        final Set<RetainedOperation.Frame> frames = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean seenAbsolute, powerGuard, abortDebit, abortObservation, failed, repeatOnClose;
        RuntimeException cleanup;
        long work;
        @Override public void executionWork(long units) {
            work += units;
            if (failed && units == 4 && repeatOnClose) {
                repeatOnClose = false;
                throw cleanup == null ? primary : cleanup;
            }
            if (!failed && abortDebit && hasGuard(graph(scope))) {
                failed = true;
                throw primary;
            }
        }
        @Override public void validationWork(long units) { fail("the caller alone settles domain visits"); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var live = graph(scope);
            seenAbsolute |= hasGuard(live);
            for (var value : live) if (value instanceof RetainedOperation.Frame frame) frames.add(frame);
            if (!failed && abortObservation && hasGuard(live)) {
                failed = true;
                throw primary;
            }
        }
        void assertReleased() {
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            for (var frame : frames) assertEquals(new RetainedGraph.Usage(0, 0, 4), RetainedGraph.measure(frame).retained());
        }
        private boolean hasGuard(Set<Object> live) {
            return powerGuard ? live.stream().anyMatch(value -> value instanceof BigInteger integer
                && integer.equals(BigInteger.valueOf(32))) : hasAbsolute(live);
        }
    }

    private static Set<Object> graph(Object root) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        var pending = new ArrayDeque<Object>();
        var visitor = new RetainedGraph.Visitor() {
            @Override public void reference(Object value) { if (value != null) pending.addLast(value); }
            @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
        };
        visitor.reference(root);
        while (!pending.isEmpty()) {
            var value = pending.removeFirst();
            if (!seen.add(value)) continue;
            visitReferences(value, visitor);
        }
        return seen;
    }

    private static void visitReferences(Object value, RetainedGraph.Visitor visitor) {
        switch (value) {
            case RetainedGraph.View view -> view.retainedReferences(visitor);
            case BinaryExpr binary -> { visitor.reference(binary.left()); visitor.reference(binary.right()); }
            case NumberExpr number -> visitor.reference(number.value());
            case ExactRational rational -> {
                visitor.reference(rational.numerator()); visitor.reference(rational.denominator());
            }
            case Collection<?> collection -> collection.forEach(visitor::reference);
            case Object[] array -> {
                for (var item : array) visitor.reference(item);
            }
            default -> { }
        }
    }

    private static boolean hasAbsolute(Set<Object> live) {
        return live.stream().anyMatch(value -> value instanceof BigInteger integer && integer.equals(BigInteger.valueOf(257)))
            && live.stream().anyMatch(value -> value instanceof BigInteger integer && integer.equals(BigInteger.valueOf(-257)));
    }

    private static CheckedLearnedSchemaModel.Bounds bounds(int coefficientBits) {
        var b = CheckedLearnedSchemaModel.Bounds.defaults();
        return new CheckedLearnedSchemaModel.Bounds(b.maximumExpressionNodes(), b.maximumPatternNodes(), b.maximumDepth(),
            coefficientBits, b.maximumExponent(), b.maximumExamples(), b.maximumPairAttempts(), b.maximumSchemas(),
            b.maximumMatchAttempts(), b.maximumCandidates());
    }

    @Test void negativeLiteralRetainsItsAbsoluteNumeratorWithoutDuplicatingTheDomainVisit() {
        var source = NumberExpr.exact("-257");
        var work = new CheckedSchemaSupport.Work();
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            CheckedSchemaSupport.domain(source, bounds(16), work);
            assertEquals(1, work.units);
            assertTrue(probe.seenAbsolute, "the allocated positive numerator must overlap the original negative one");
            assertTrue(probe.work > work.units);
        }
        probe.assertReleased();
    }

    @Test void coefficientRejectionStillObservesItsAbsoluteNumeratorAndOneVisitedNode() {
        var source = NumberExpr.exact("-257");
        var work = new CheckedSchemaSupport.Work();
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var rejected = assertThrows(CheckedSchemaSupport.DomainRejected.class,
                () -> CheckedSchemaSupport.domain(source, bounds(8), work));
            assertEquals("checked rational literal size limit", rejected.getMessage());
            assertEquals(1, work.units);
            assertTrue(probe.seenAbsolute);
        }
        probe.assertReleased();
    }

    @Test void failedAbsoluteAllocationDebitCannotHideTheActualTemporaryOrItsVisitedPrefix() {
        var source = NumberExpr.exact("-257");
        var work = new CheckedSchemaSupport.Work();
        var probe = new Probe();
        probe.abortDebit = true;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, () -> CheckedSchemaSupport.domain(source, bounds(8), work)));
            assertEquals(1, work.units);
            assertTrue(probe.seenAbsolute);
        }
        probe.assertReleased();
    }

    @Test void technicalAbortRemainsPrimaryWhenLiteralCleanupRepeatsItOrThrowsAnotherFailure() {
        for (boolean distinct : List.of(false, true)) {
            var source = NumberExpr.exact("-257");
            var work = new CheckedSchemaSupport.Work();
            var probe = new Probe();
            probe.abortObservation = true;
            probe.repeatOnClose = true;
            if (distinct) probe.cleanup = new IllegalStateException("literal cleanup failed");
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.primary, assertThrows(Limit.class, () -> CheckedSchemaSupport.domain(source, bounds(8), work)));
                assertEquals(1, work.units);
                assertEquals(distinct ? List.of(probe.cleanup) : List.of(), List.of(probe.primary.getSuppressed()));
            }
            probe.assertReleased();
        }
    }

    @Test void powerGuardRetainsItsConvertedUpperBoundForAcceptedAndRejectedPowers() {
        for (int exponent : List.of(2, 33)) {
            var source = new BinaryExpr(new VariableExpr("x"), BinaryOperator.POW, NumberExpr.exact(Integer.toString(exponent)));
            var work = new CheckedSchemaSupport.Work();
            var probe = new Probe();
            probe.powerGuard = true;
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                if (exponent == 2) CheckedSchemaSupport.domain(source, bounds(16), work);
                else assertThrows(CheckedSchemaSupport.DomainRejected.class, () -> CheckedSchemaSupport.domain(source, bounds(16), work));
                assertEquals(exponent == 2 ? 3 : 1, work.units);
                assertTrue(probe.seenAbsolute, "BigInteger.valueOf(maximumExponent) is an actual guard operand");
            }
            probe.assertReleased();
        }
    }

    @Test void powerBoundAllocationAbortKeepsItsOwnerAndTheSingleVisitedParent() {
        var source = new BinaryExpr(new VariableExpr("x"), BinaryOperator.POW, NumberExpr.exact("2"));
        var work = new CheckedSchemaSupport.Work();
        var probe = new Probe();
        probe.powerGuard = true;
        probe.abortDebit = true;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.primary, assertThrows(Limit.class, () -> CheckedSchemaSupport.domain(source, bounds(16), work)));
            assertEquals(1, work.units);
            assertTrue(probe.seenAbsolute);
        }
        probe.assertReleased();
    }
}
