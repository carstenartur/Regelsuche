package de.regelsuche.json;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class JsonWriterOwnershipTest {
    private static final class DebitFailure extends RuntimeException { }
    private static final class ObservationFailure extends RuntimeException { }
    private static final class CleanupFailure extends RuntimeException { }
    private static final class CallbackFailure extends RuntimeException { }

    private record Snapshot(Set<Object> objects, Set<String> strings, List<String> buffers) {
        boolean overlaps(String text) { return strings.contains(text) && buffers.contains(text); }
    }

    private static final class Probe implements RetainedOperation.Sink {
        RetainedOperation scope;
        long work, failUnits = -1, failAfterWork = Long.MAX_VALUE;
        boolean failed, failObservationAfterSinkAllocation, observationFailed, failCleanup;
        RuntimeException repeatedCleanup;
        final DebitFailure debitFailure = new DebitFailure();
        final List<Snapshot> snapshots = new ArrayList<>();
        @Override public void executionWork(long units) {
            work = Math.addExact(work, units);
            if (!failed && (units == failUnits || work >= failAfterWork)) {
                failed = true;
                throw debitFailure;
            }
            if (repeatedCleanup != null && units == 4) throw repeatedCleanup;
            if (failCleanup && observationFailed && units == 4) {
                failCleanup = false;
                throw new CleanupFailure();
            }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var pending = new ArrayDeque<Object>();
            Set<Object> objects = Collections.newSetFromMap(new IdentityHashMap<>());
            var strings = new HashSet<String>();
            var buffers = new ArrayList<String>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.removeFirst();
                if (!objects.add(value)) continue;
                if (value instanceof String text) strings.add(text);
                if (value instanceof StringBuilder builder) buffers.add(builder.toString());
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] values) for (Object item : values) visitor.reference(item);
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
            }
            snapshots.add(new Snapshot(objects, strings, buffers));
            if (failObservationAfterSinkAllocation && objects.stream().anyMatch(value ->
                    value instanceof char[] buffer && buffer.length == 1000)) {
                failObservationAfterSinkAllocation = false;
                observationFailed = true;
                throw new ObservationFailure();
            }
        }
        boolean overlaps(String text) { return snapshots.stream().anyMatch(snapshot -> snapshot.overlaps(text)); }
    }

    private static final class TrackingWriter extends Writer implements RetainedGraph.View {
        final StringBuilder written = new StringBuilder();
        String lastChunk;
        char[] scratch;
        Throwable failure;
        int calls, largestChunk;
        @Override public void write(String text) throws IOException {
            calls++;
            largestChunk = Math.max(largestChunk, text.length());
            lastChunk = text;
            if (failure != null) {
                scratch = new char[1000];
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw (Error) failure;
            }
            written.append(text);
        }
        @Override public void write(char[] text, int offset, int length) { fail("preserve the existing String write callback"); }
        @Override public void flush() { fail("sink flush remains caller-owned"); }
        @Override public void close() { fail("sink close remains caller-owned"); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) {
            visitor.reference(written); visitor.reference(lastChunk); visitor.reference(scratch);
        }
    }

    private record Rendered(String output, long work, int checkpoints) { }
    private static Rendered renderMeasured(String value) {
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            long before = probe.work;
            String output = new JsonWriter().value(value).toString();
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            return new Rendered(output, probe.work - before, probe.snapshots.size());
        }
    }

    @Test void appendAndEscapingWorkGrowWithoutPerCharacterOwnershipScans() {
        var small = renderMeasured("x".repeat(32));
        var large = renderMeasured("x".repeat(512));
        var escaped = renderMeasured("\u0001".repeat(512));
        assertEquals("\"" + "x".repeat(512) + "\"", large.output());
        assertEquals("\"" + "\\u0001".repeat(512) + "\"", escaped.output());
        assertTrue(large.work() >= small.work() + 2L * (512 - 32), "input visits and output appends are paid");
        assertTrue(escaped.work() > large.work(), "six-code-unit escapes require more work than ordinary characters");
        assertTrue(large.checkpoints() <= small.checkpoints() + 2, "normal ownership scans are bounded per operation");
        assertTrue(escaped.checkpoints() <= large.checkpoints() + 2, "escaping does not scan the whole owner per character");
    }

    @Test void completedOutputCopySurvivesItsFailedDebit() {
        String expected = "[\"output_marker\"]";
        var writer = new JsonWriter().beginArray().value("output_marker").endArray();
        var probe = new Probe(); probe.failUnits = expected.length() + 1L;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.debitFailure, assertThrows(DebitFailure.class, writer::toString));
            assertTrue(probe.overlaps(expected), "the actual completed String and its source builder overlap before cleanup");
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            assertEquals(expected, writer.toString());
        }
        assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
    }

    @Test void failedFlushCopyDebitOwnsTheChunkAndSinkBeforeCallingTheSink() {
        String expected = "[\"flush_marker\"]";
        var sink = new TrackingWriter();
        var writer = new JsonWriter(sink).beginArray().value("flush_marker").endArray();
        var probe = new Probe(); probe.failUnits = expected.length() + 1L;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.debitFailure, assertThrows(DebitFailure.class, writer::flush));
            assertTrue(probe.overlaps(expected));
            assertTrue(probe.snapshots.stream().anyMatch(snapshot -> snapshot.objects().contains(sink)));
            assertEquals(0, sink.calls, "a failed completed-copy debit must not invoke the stream callback");
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            writer.flush();
            assertEquals(expected, sink.written.toString());
            assertEquals(1, sink.calls);
        }
        assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
    }

    private record NumberCase(String text, Consumer<JsonWriter> append) { }
    @Test void convertedNumberTextSurvivesItsFailedDebit() {
        var huge = new BigInteger("12345678901234567890123456789");
        for (var number : List.of(
                new NumberCase("-2147483648", writer -> writer.property("n", Integer.MIN_VALUE)),
                new NumberCase("-9223372036854775808", writer -> writer.property("n", Long.MIN_VALUE)),
                new NumberCase("1.25E100", writer -> writer.property("n", 1.25e100)),
                new NumberCase(huge.toString(), writer -> writer.integerProperty("n", huge)))) {
            var writer = new JsonWriter().beginObject();
            var probe = new Probe(); probe.failUnits = number.text().length() + 1L;
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                assertSame(probe.debitFailure, assertThrows(DebitFailure.class, () -> number.append().accept(writer)));
                assertTrue(probe.snapshots.stream().anyMatch(snapshot -> snapshot.strings().contains(number.text())
                    && snapshot.buffers().contains("{\"n\":")), "actual number text is published before its debit can abort");
                assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            }
            assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
        }
    }

    @Test void convertedNumberTextOverlapsThePopulatedBuilderUntilAppendFinishes() {
        String number = "-9223372036854775808";
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertEquals("{\"n\":" + number + "}", new JsonWriter().beginObject()
                .property("n", Long.MIN_VALUE).endObject().toString());
            assertTrue(probe.snapshots.stream().anyMatch(snapshot -> snapshot.strings().contains(number)
                && snapshot.buffers().contains("{\"n\":" + number)),
                "number text remains owned while its characters have already enlarged the destination builder");
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
        }
    }

    @Test void historicalBigIntegerObjectAppendStillAcceptsNullConversionText() {
        var value = new BigInteger("1") {
            @Override public String toString() { return null; }
        };
        assertEquals("{\"n\":null}", new JsonWriter().beginObject().integerProperty("n", value).endObject().toString());
    }

    @Test void abortedCharacterWorkObservesTheMutatedBuilderAndLiveInput() {
        String input = "x".repeat(1000);
        var writer = new JsonWriter();
        var probe = new Probe(); probe.failAfterWork = 200;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(probe.debitFailure, assertThrows(DebitFailure.class, () -> writer.value(input)));
            assertTrue(probe.snapshots.stream().anyMatch(snapshot -> snapshot.objects().contains(input)
                && snapshot.buffers().stream().anyMatch(text -> text.length() > 1 && text.length() < input.length())),
                "a mid-append work abort observes current output and its borrowed input before releasing the frame");
            assertTrue(probe.snapshots.size() < 10);
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            assertEquals("true", new JsonWriter().value(true).toString());
        }
        assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
    }

    @Test void streamFailuresKeepTheirPrimaryThrowableAndObserveSinkMutationBeforeCleanup() {
        String expected = "[\"callback_marker\"]";
        for (Throwable original : List.of(new IOException("stream failure"), new CallbackFailure(), new AssertionError("stream error"))) {
            var sink = new TrackingWriter(); sink.failure = original;
            var writer = new JsonWriter(sink).beginArray().value("callback_marker").endArray();
            var probe = new Probe(); probe.failObservationAfterSinkAllocation = true; probe.failCleanup = true;
            try (var scope = RetainedOperation.open(probe)) {
                probe.scope = scope;
                Throwable failure = assertThrows(Throwable.class, writer::flush);
                if (original instanceof IOException) assertSame(original, assertInstanceOf(UncheckedIOException.class, failure).getCause());
                else assertSame(original, failure);
                assertTrue(probe.snapshots.stream().anyMatch(snapshot -> snapshot.overlaps(expected)
                    && snapshot.objects().contains(sink.scratch)), "the chunk, builder and callback allocation remain jointly owned");
                assertEquals(2, failure.getSuppressed().length);
                assertInstanceOf(ObservationFailure.class, failure.getSuppressed()[0]);
                assertInstanceOf(CleanupFailure.class, failure.getSuppressed()[1]);
                assertEquals(0, RetainedGraph.measure(scope).retained().characters());
                sink.failure = null;
                writer.flush();
                assertEquals(expected, sink.written.toString(), "failed flush keeps the buffered chunk for a retry");
                assertEquals(2, sink.calls);
            }
            assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
        }
    }

    @Test void repeatedFrameCleanupFailureCannotReplaceTheOriginalStreamFailure() {
        var original = new CallbackFailure();
        var sink = new TrackingWriter(); sink.failure = original;
        var writer = new JsonWriter(sink).value("repeat");
        var probe = new Probe(); probe.repeatedCleanup = original;
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertSame(original, assertThrows(Throwable.class, writer::flush));
            assertEquals(0, original.getSuppressed().length, "cleanup must not attempt to suppress the primary onto itself");
            probe.repeatedCleanup = null;
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            assertEquals("true", new JsonWriter().value(true).toString());
        }
    }

    @Test void nativeStreamingStillKeepsExistingChunkBoundariesAndAllEscapeBytes() {
        String value = "\"\\\n\r\t\b\f\u0000\u001f😀".repeat(1000);
        String expected = new JsonWriter().beginObject().property("v", value).endObject().toString();
        var sink = new TrackingWriter();
        var probe = new Probe();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            var writer = new JsonWriter(sink).beginObject().property("v", value).endObject();
            writer.flush();
            assertEquals(expected, sink.written.toString());
            assertTrue(sink.largestChunk <= 8200);
            assertTrue(probe.work >= expected.length());
            assertTrue(probe.snapshots.size() < 100, "chunk observation remains bounded independently of character count");
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
        }
        assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
    }

    @Test void nativeNestedBodiesPreserveCallbackResultsAndOriginalFailure() {
        var probe = new Probe();
        var original = new CallbackFailure();
        try (var scope = RetainedOperation.open(probe)) {
            probe.scope = scope;
            assertEquals("{\"nested\":{\"items\":[1,true,\"x\"]}}", new JsonWriter().beginObject()
                .object("nested", out -> out.array("items", values -> values.value(1).value(true).value("x")))
                .endObject().toString());
            var writer = new JsonWriter().beginObject();
            assertSame(original, assertThrows(CallbackFailure.class, () -> writer.object("body", out -> {
                out.property("written", true);
                throw original;
            })));
            assertEquals("{\"body\":{\"written\":true}}", writer.endObject().endObject().toString());
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
        }
        assertEquals(0, RetainedGraph.measure(probe.scope).retained().characters());
    }
}
