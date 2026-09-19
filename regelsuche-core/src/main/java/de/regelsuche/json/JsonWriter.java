package de.regelsuche.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Objects;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tiny fluent JSON writer used as central rendering primitive.
 *
 * <p>Replaces the previous string-concatenation approach to keep the JSON
 * export consistent (escaping, comma handling, ordering) without pulling in a
 * large external dependency.</p>
 */
public final class JsonWriter {
    private final StringBuilder builder = new StringBuilder();
    private final Deque<Boolean> firstEntry = new ArrayDeque<>();
    private static final int STREAM_BUFFER_SIZE = 8192;
    private final Writer sink;

    public JsonWriter() {
        sink = null;
    }

    /**
     * Writes in bounded chunks instead of retaining the complete document.
     * Call {@link #flush()} after the last value. The caller owns the sink:
     * this class never flushes or closes it. I/O failures are propagated as
     * {@link UncheckedIOException} so nested rendering callbacks stay usable.
     */
    public JsonWriter(Writer sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /** Writes the remaining chunk without flushing or closing the caller's sink. */
    public void flush() {
        if (sink == null || builder.isEmpty()) return;
        try {
            sink.write(builder.toString());
            builder.setLength(0);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private void drainIfNeeded() {
        if (sink != null && builder.length() >= STREAM_BUFFER_SIZE) flush();
    }

    public JsonWriter beginObject() {
        drainIfNeeded();
        builder.append('{');
        firstEntry.push(true);
        return this;
    }

    public JsonWriter endObject() {
        if (firstEntry.isEmpty()) {
            throw new IllegalStateException("No open object");
        }
        firstEntry.pop();
        drainIfNeeded();
        builder.append('}');
        return this;
    }

    public JsonWriter beginArray() {
        drainIfNeeded();
        builder.append('[');
        firstEntry.push(true);
        return this;
    }

    public JsonWriter endArray() {
        if (firstEntry.isEmpty()) {
            throw new IllegalStateException("No open array");
        }
        firstEntry.pop();
        drainIfNeeded();
        builder.append(']');
        return this;
    }

    public JsonWriter object(String key, Consumer<JsonWriter> body) {
        comma();
        appendKey(key);
        beginObject();
        body.accept(this);
        return endObject();
    }

    public JsonWriter array(String key, Consumer<JsonWriter> body) {
        comma();
        appendKey(key);
        beginArray();
        body.accept(this);
        return endArray();
    }

    public JsonWriter property(String key, String value) {
        comma();
        appendKey(key);
        appendString(value);
        return this;
    }

    public JsonWriter property(String key, int value) {
        comma();
        appendKey(key);
        builder.append(value);
        return this;
    }

    public JsonWriter property(String key, long value) {
        comma();
        appendKey(key);
        builder.append(value);
        return this;
    }

    public JsonWriter property(String key, double value) {
        comma();
        appendKey(key);
        builder.append(value);
        return this;
    }

    public JsonWriter property(String key, boolean value) {
        comma();
        appendKey(key);
        builder.append(value);
        return this;
    }

    public JsonWriter nullProperty(String key) {
        comma();
        appendKey(key);
        builder.append("null");
        return this;
    }

    public JsonWriter stringArray(String key, List<String> values) {
        return array(key, writer -> values.forEach(writer::value));
    }

    public JsonWriter booleanArray(String key, List<Boolean> values) {
        return array(key, writer -> values.forEach(writer::value));
    }

    public JsonWriter value(String value) {
        comma();
        appendString(value);
        return this;
    }

    public JsonWriter value(int value) {
        comma();
        builder.append(value);
        return this;
    }

    /** Emit an explicitly numeric value inside an array context. */
    public JsonWriter numberValue(int value) {
        return value(value);
    }

    public JsonWriter value(boolean value) {
        comma();
        builder.append(value);
        return this;
    }

    public JsonWriter objectValue(Consumer<JsonWriter> body) {
        comma();
        beginObject();
        body.accept(this);
        return endObject();
    }

    /** Emit a nested array value inside an array context. */
    public JsonWriter arrayValue(Consumer<JsonWriter> body) {
        comma();
        beginArray();
        body.accept(this);
        return endArray();
    }

    private void comma() {
        drainIfNeeded();
        if (firstEntry.isEmpty()) {
            return;
        }
        boolean first = firstEntry.pop();
        if (!first) {
            builder.append(',');
        }
        firstEntry.push(false);
    }

    private void appendKey(String key) {
        appendString(key);
        builder.append(':');
    }

    private void appendString(String value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            drainIfNeeded();
            char current = value.charAt(i);
            switch (current) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
    }

    @Override
    public String toString() {
        if (sink != null) throw new IllegalStateException("Streaming JsonWriter does not retain its output");
        return builder.toString();
    }
}
