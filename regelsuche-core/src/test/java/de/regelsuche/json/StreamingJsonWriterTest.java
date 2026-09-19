package de.regelsuche.json;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingJsonWriterTest {
    @Test
    void boundedChunksPreserveUnicodeEscapesNumbersAndNestedValues() {
        var sink = new TrackingWriter();
        var streamed = new JsonWriter(sink);
        var buffered = new JsonWriter();
        render(streamed);
        render(buffered);
        streamed.flush();
        assertEquals(buffered.toString(), sink.toString());
        assertTrue(sink.largestChunk <= 8200, "one field must not make the output buffer unbounded");
        assertFalse(sink.flushed);
        assertFalse(sink.closed);
        assertThrows(IllegalStateException.class, streamed::toString);
    }

    @Test
    void surrogatePairsRemainValidAcrossChunkBoundaries() throws IOException {
        String value = "x".repeat(8185) + "😀" + "x".repeat(8189) + "𝕏";
        var expected = new JsonWriter().beginObject().property("v", value).endObject().toString();
        var bytes = new ByteArrayOutputStream();
        try (var destination = new OutputStreamWriter(bytes, StandardCharsets.UTF_8)) {
            var writer = new JsonWriter(destination);
            writer.beginObject().property("v", value).endObject();
            writer.flush();
        }
        assertEquals(expected, bytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void ioFailureRetainsItsOriginalCause() {
        var failure = new IOException("destination unavailable");
        var writer = new JsonWriter(new Writer() {
            @Override public void write(char[] c, int off, int len) throws IOException { throw failure; }
            @Override public void flush() { fail("sink is caller-owned"); }
            @Override public void close() { fail("sink is caller-owned"); }
        });
        writer.beginArray().value("short").endArray();
        assertSame(failure, assertThrows(UncheckedIOException.class, writer::flush).getCause());
    }

    @Test
    void bufferedFlushDoesNotEraseTheBufferedResult() {
        var writer = new JsonWriter().beginArray().value("keep").endArray();
        writer.flush();
        assertEquals("[\"keep\"]", writer.toString());
        assertThrows(NullPointerException.class, () -> new JsonWriter(null));
    }

    private static void render(JsonWriter writer) {
        writer.beginObject().property("large", "B ∈ {\"x\",\\y}\n\t\r\b\f\u0000😀".repeat(1000));
        writer.array("many", out -> {
            for (int i = 0; i < 20000; i++) out.value(i);
            out.value(true).value((String) null).arrayValue(nested -> nested.value("inner"));
        });
        writer.object("nested", out -> out.property("int", -7).property("long", Long.MAX_VALUE)
            .property("real", 0.25).property("bool", false).nullProperty("null")
            .stringArray("strings", List.of("one", "two")).booleanArray("flags", List.of(true, false)));
        writer.endObject();
    }

    private static final class TrackingWriter extends StringWriter {
        private int largestChunk;
        private boolean flushed;
        private boolean closed;
        @Override public void write(String text) {
            largestChunk = Math.max(largestChunk, text.length());
            super.write(text);
        }
        @Override public void flush() { flushed = true; }
        @Override public void close() { closed = true; }
    }
}
