package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import java.util.*;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

class ExpressionFormatterTemporaryOwnershipTest {
    private static final class GrowthLimit extends RuntimeException { }
    private static final class CallbackFailure extends RuntimeException { }
    private static final class CleanupFailure extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        Expr input;
        long work, previousWork, growthWork, peakCharacters, abortCharactersAt = Long.MAX_VALUE;
        int queuedActions, buffers, checkpoints;
        boolean inputMissing, growth, abortGrowth, unwrittenReplacement;
        boolean parenthesisGrowth, renderedNumberOwned;
        String renderedNumber;
        String expectedOutput;
        boolean actualResultCopyOwned;
        boolean throwCleanupAfterCallback, callbackStateObserved;
        @Override public void executionWork(long units) {
            if (throwCleanupAfterCallback && callbackStateObserved && units == 1) {
                throwCleanupAfterCallback = false;
                throw new CleanupFailure();
            }
            work = Math.addExact(work, units);
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            checkpoints++;
            peakCharacters = Math.max(peakCharacters, RetainedGraph.measure(scope).retained().characters());
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            boolean oldBuffer = false, replacement = false, parenthesisReplacement = false;
            int currentBuffers = 0;
            while (!pending.isEmpty()) {
                var value = pending.remove();
                if (!seen.add(value)) continue;
                if (value instanceof char[] buffer) {
                    currentBuffers++;
                    if (buffer.length == 16) oldBuffer = true;
                    if (buffer.length == 34) parenthesisReplacement = true;
                    if (buffer.length == 80) {
                        replacement = true;
                        unwrittenReplacement = true;
                        for (char item : buffer) if (item != 0) unwrittenReplacement = false;
                    }
                }
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] array) for (var item : array) visitor.reference(item);
                else if (value instanceof Collection<?> collection) {
                    if (value instanceof ArrayDeque<?> && collection.stream().anyMatch(item ->
                            item.getClass().getEnclosingClass() == ExpressionFormatter.class))
                        queuedActions = Math.max(queuedActions, collection.size());
                    collection.forEach(visitor::reference);
                } else if (value instanceof BinaryExpr binary) {
                    visitor.reference(binary.left()); visitor.reference(binary.right());
                } else if (value instanceof FunctionExpr function) visitor.reference(function.arguments());
            }
            buffers = Math.max(buffers, currentBuffers);
            if (expectedOutput != null && seen.stream().anyMatch(value -> expectedOutput.equals(value))) {
                actualResultCopyOwned |= seen.stream().anyMatch(value -> value instanceof char[] buffer
                    && matches(buffer, expectedOutput));
            }
            if (oldBuffer && parenthesisReplacement) {
                parenthesisGrowth = true;
                renderedNumberOwned = seen.stream().anyMatch(value -> value instanceof String text && text.equals(renderedNumber));
            }
            if (input != null && !seen.contains(input)) inputMissing = true;
            if (oldBuffer && replacement) {
                growth = true;
                growthWork = work - previousWork;
                if (abortGrowth) throw new GrowthLimit();
            }
            previousWork = work;
            if (peakCharacters >= 1_000) callbackStateObserved = true;
            if (peakCharacters >= abortCharactersAt) throw new GrowthLimit();
        }
    }
    private static boolean matches(char[] buffer, String text) {
        if (buffer.length < text.length()) return false;
        for (int index = 0; index < text.length(); index++) if (buffer[index] != text.charAt(index)) return false;
        return true;
    }
    private static final class AllocatingEmission implements LongConsumer, RetainedGraph.View {
        char[] captured;
        boolean fail;
        @Override public void accept(long units) {
            captured = new char[1_000];
            RetainedOperation.work(captured.length);
            if (fail) throw new CallbackFailure();
        }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(captured); }
    }

    @Test void parenthesisGrowthKeepsTheAlreadyRenderedNumberAlive() {
        for (var number : List.of(new NumberExpr(-2), NumberExpr.exact("1/3"))) {
            var input = new BinaryExpr(new VariableExpr("abcdefghijklm"), BinaryOperator.POW, number);
            var observation = new Observation(); observation.input = input;
            observation.renderedNumber = number.value().isInteger() ? "-2" : "1 / 3";
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                assertEquals("abcdefghijklm ^ (" + observation.renderedNumber + ")", ExpressionFormatter.format(input));
            }
            assertTrue(observation.parenthesisGrowth, "the opening parenthesis grows the real full buffer");
            assertTrue(observation.renderedNumberOwned, "the final numeric fragment already exists at that growth checkpoint");
            assertFalse(observation.inputMissing);
            assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
        }
    }

    @Test void mutableCallbackAllocationIsObservedBeforeSuccessfulReturn() {
        for (Expr input : List.of(new VariableExpr("x"), new FunctionExpr("f", List.of()))) {
            var observation = new Observation(); var emitted = new AllocatingEmission();
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                assertEquals(ExpressionFormatter.format(input), ExpressionFormatter.formatMeasured(input, emitted));
            }
            assertTrue(observation.peakCharacters >= 1_001, "callback-created storage must be observed before owner release");
            assertTrue(observation.work >= 1_000);
            assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
        }
    }

    @Test void failedCallbackKeepsItsWorkAndOwnershipWithoutLosingTheOriginalFailure() {
        for (Expr input : List.of(new VariableExpr("x"), new FunctionExpr("f", List.of()))) {
            var observation = new Observation(); observation.abortCharactersAt = 1_000;
            var emitted = new AllocatingEmission(); emitted.fail = true;
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                var failure = assertThrows(CallbackFailure.class, () -> ExpressionFormatter.formatMeasured(input, emitted));
                assertEquals(1, failure.getSuppressed().length);
                assertInstanceOf(GrowthLimit.class, failure.getSuppressed()[0]);
                assertTrue(observation.peakCharacters >= 1_001);
                assertTrue(observation.work >= 1_000);
            }
            assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
        }
    }

    @Test void failedFragmentCleanupCannotReplaceTheOriginalCallbackFailure() {
        var input = new NumberExpr(-2);
        var observation = new Observation(); observation.throwCleanupAfterCallback = true;
        var emitted = new AllocatingEmission(); emitted.fail = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(CallbackFailure.class, () -> ExpressionFormatter.formatMeasured(input, emitted));
            assertEquals(1, failure.getSuppressed().length);
            assertInstanceOf(CleanupFailure.class, failure.getSuppressed()[0]);
            assertTrue(observation.callbackStateObserved);
            assertTrue(observation.work >= 1_000);
        }
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
    }

    @Test void textAlreadyOwnedByTheSourceOrCurrentActionNeedsNoRepeatedWholeGraphScan() {
        var input = new FunctionExpr("f", List.of(new VariableExpr("a"), new VariableExpr("b")));
        var observation = new Observation(); observation.input = input; observation.expectedOutput = "f(a, b)";
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertEquals(observation.expectedOutput, ExpressionFormatter.format(input));
        }
        assertTrue(observation.checkpoints <= 3,
            "observe workspace, scheduled owners and copied result; existing input/action text adds no owned object");
        assertTrue(observation.queuedActions >= 3);
        assertTrue(observation.actualResultCopyOwned);
        assertFalse(observation.inputMissing);
        assertTrue(observation.work > observation.expectedOutput.length());
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
    }
    private static final class Emission implements LongConsumer, RetainedGraph.View {
        long count;
        @Override public void accept(long units) { count = Math.addExact(count, units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    @Test void actualPendingActionsInputAndOutputCopiesOverlapUntilTheHandoff() {
        var input = new ExpressionParser().parseTerm("f(a, b + c, (x^y)^z)");
        var observation = new Observation(); observation.input = input;
        observation.expectedOutput = "f(a, b + c, (x ^ y) ^ z)";
        String output;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            output = ExpressionFormatter.format(input);
        }
        assertEquals("f(a, b + c, (x ^ y) ^ z)", output);
        assertTrue(observation.queuedActions >= 5, "real pending formatter actions remain owned");
        assertTrue(observation.actualResultCopyOwned, "the complete result String overlaps its actual populated backing buffer");
        assertFalse(observation.inputMissing);
        assertTrue(observation.work > output.length());
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
    }

    @Test void allocatedGrowthIsPaidAndOwnedBeforeAnAbortingCheckpoint() {
        var input = new FunctionExpr("a".repeat(80), List.of());
        var observation = new Observation(); observation.input = input; observation.abortGrowth = true;
        var emitted = new Emission();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertThrows(GrowthLimit.class, () -> ExpressionFormatter.formatMeasured(input, emitted));
            assertTrue(observation.growth);
            assertTrue(observation.unwrittenReplacement);
            assertEquals(82, observation.growthWork, "80 allocated characters and frame acquisition are paid independently of delegated emission");
            assertTrue(observation.peakCharacters >= 176, "input, old buffer and replacement overlap");
            assertFalse(observation.inputMissing);
            observation.abortGrowth = false;
            assertEquals(input.name() + "()", ExpressionFormatter.format(input));
        }
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
    }

    @Test void singleVariableReusesItsExistingNameWithoutAFormattingBuffer() {
        var input = new VariableExpr("a_long_variable_name");
        var observation = new Observation(); observation.input = input;
        var emitted = new Emission();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertSame(input.name(), ExpressionFormatter.formatMeasured(input, emitted));
        }
        assertEquals(input.name().length(), emitted.count);
        assertEquals(0, observation.buffers);
        assertEquals(0, observation.queuedActions);
        assertFalse(observation.inputMissing);
        assertTrue(observation.work > 4, "the input and callback ownership is still paid");
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
    }

    @Test void measuredOutputStillDelegatesExactlyTheEmittedCharacters() {
        var input = new ExpressionParser().parseTerm("x^(a*b) + f(y, -2)");
        var observation = new Observation(); observation.input = input;
        var emitted = new Emission();
        String output;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            output = ExpressionFormatter.formatMeasured(input, emitted);
        }
        assertEquals(output.length(), emitted.count);
        assertTrue(observation.queuedActions > 0);
        assertFalse(observation.inputMissing);
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void opaqueMeasuredCallbackCannotSilentlyClaimBoundedNativeOwnership() {
        var input = new VariableExpr("x"); var observation = new Observation();
        LongConsumer opaque = units -> { };
        assertEquals("x", ExpressionFormatter.formatMeasured(input, opaque), "historical public formatting remains available");
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertThrows(RetainedGraph.Unmeasured.class, () -> ExpressionFormatter.formatMeasured(input, opaque));
            assertEquals("x", ExpressionFormatter.format(input), "failed acquisition restores the enclosing scope");
        }
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
    }
}
