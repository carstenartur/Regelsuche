package de.regelsuche.parse;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.retention.*;
import java.util.*;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;

class ExpressionFormatterTemporaryOwnershipTest {
    private static final class GrowthLimit extends RuntimeException { }
    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        Expr input;
        long work, previousWork, growthWork, peakCharacters;
        int queuedActions, buffers;
        boolean inputMissing, growth, abortGrowth, unwrittenReplacement;
        @Override public void executionWork(long units) { work = Math.addExact(work, units); }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void checkpoint() {
            peakCharacters = Math.max(peakCharacters, RetainedGraph.measure(scope).retained().characters());
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            boolean oldBuffer = false, replacement = false;
            int currentBuffers = 0;
            while (!pending.isEmpty()) {
                var value = pending.remove();
                if (!seen.add(value)) continue;
                if (value instanceof char[] buffer) {
                    currentBuffers++;
                    if (buffer.length == 16) oldBuffer = true;
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
            if (input != null && !seen.contains(input)) inputMissing = true;
            if (oldBuffer && replacement) {
                growth = true;
                growthWork = work - previousWork;
                if (abortGrowth) throw new GrowthLimit();
            }
            previousWork = work;
        }
    }
    private static final class Emission implements LongConsumer, RetainedGraph.View {
        long count;
        @Override public void accept(long units) { count = Math.addExact(count, units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
    }

    @Test void actualPendingActionsInputAndOutputCopiesOverlapUntilTheHandoff() {
        var input = new ExpressionParser().parseTerm("f(a, b + c, (x^y)^z)");
        var observation = new Observation(); observation.input = input;
        String output;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            output = ExpressionFormatter.format(input);
        }
        assertEquals("f(a, b + c, (x ^ y) ^ z)", output);
        assertTrue(observation.queuedActions >= 5, "real pending formatter actions remain owned");
        assertTrue(observation.peakCharacters >= output.length() * 2L, "the result overlaps its actual backing buffer");
        assertFalse(observation.inputMissing);
        assertTrue(observation.work > output.length());
        assertEquals(0, RetainedGraph.measure(observation.scope).retained().characters());
    }

    @Test void allocatedGrowthIsPaidAndOwnedBeforeAnAbortingCheckpoint() {
        var input = new FunctionExpr("a".repeat(80), List.of());
        var observation = new Observation(); observation.input = input; observation.abortGrowth = true;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            assertThrows(GrowthLimit.class, () -> ExpressionFormatter.format(input));
            assertTrue(observation.growth);
            assertTrue(observation.unwrittenReplacement);
            assertTrue(observation.growthWork >= 82, "80 allocated characters and frame acquisition are already paid");
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
