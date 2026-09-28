package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.*;
import org.junit.jupiter.api.Test;

class MatcherDescriptorOwnershipTest {
    private static final class RenderingAbort extends RuntimeException { }

    private static final class Observation implements RetainedOperation.Sink {
        RetainedOperation scope;
        String input, expected, output;
        char[] buffer;
        boolean sawOverlap, abortBuffer, sawFailedBuffer;
        RenderingAbort failure;
        long paid;

        @Override public void executionWork(long units) {
            paid += units;
            if (abortBuffer && failure == null && graph().stream().anyMatch(value -> value instanceof char[])) {
                failure = new RenderingAbort(); throw failure;
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }

        private Set<Object> graph() {
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());
            var pending = new ArrayDeque<Object>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value,Class<?> type) { assertEquals(type,value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.remove(); if (!seen.add(value)) continue;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] values) for (Object item : values) visitor.reference(item);
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
            }
            return seen;
        }

        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var values = graph();
            for (Object value : values) {
                if (value instanceof char[] chars) {
                    buffer = chars;
                    sawFailedBuffer |= failure != null;
                }
                if (value instanceof String text && text.equals(expected)) output = text;
            }
            sawOverlap |= values.contains(input) && buffer != null && values.contains(buffer)
                && output != null && values.contains(output);
        }
    }

    @Test void actualInputBufferAndCompletedTextRemainOwnedTogether() {
        String name = "δ🙂:".repeat(100);
        var observation = new Observation(); observation.input = name;
        observation.expected = "16:literal-variable" + name.length() + ":" + name;
        String result;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            result = ExprMatcher.literalVariable(name).canonicalDescriptor();
        }
        assertEquals(observation.expected,result);
        assertSame(result,observation.output);
        assertNotNull(observation.buffer,"the actual character assembly must be owned, not just its final String");
        assertEquals(result,new String(observation.buffer));
        assertTrue(observation.sawOverlap,"input, actual populated buffer and final String overlap before return");
        assertTrue(observation.paid >= 2L * result.length(),"writes and final copy are separate completed work");
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void aFailedBufferDebitStillObservesTheAllocatedBufferBeforeRelease() {
        var observation = new Observation(); observation.abortBuffer = true;
        observation.input = "long-name".repeat(20);
        observation.expected = "16:literal-variable" + observation.input.length() + ":" + observation.input;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(RenderingAbort.class,
                () -> ExprMatcher.literalVariable(observation.input).canonicalDescriptor());
            assertSame(observation.failure,failure);
            assertTrue(observation.sawFailedBuffer,"failed completed-allocation debit must observe its actual owner");
            assertNull(observation.output,"a buffer debit can stop before final text construction");
        }
        assertTrue(observation.paid > 0);
        assertEquals(0,RetainedGraph.measure(observation.scope).retained().nodes());
    }

    @Test void decimalLengthBoundariesPreserveTheHistoricalUtf16Encoding() {
        for (int length : new int[]{1,9,10,99,100,999,1000}) {
            String name = "x".repeat(length);
            assertEquals("16:literal-variable" + length + ":" + name,
                ExprMatcher.literalVariable(name).canonicalDescriptor());
        }
        String name = "🙂δ";
        assertEquals("16:literal-variable3:" + name,ExprMatcher.literalVariable(name).canonicalDescriptor());
    }

    @Test void nestedDescriptorsKeepTheirHistoricalFraming() {
        var nested = ExprMatcher.allOf(ExprMatcher.literalVariable("x"),ExprMatcher.any());
        String children = "12:matcher-list20:16:literal-variable1:x5:3:any";
        assertEquals("6:all-of" + children.length() + ":" + children,nested.canonicalDescriptor());
    }
}
