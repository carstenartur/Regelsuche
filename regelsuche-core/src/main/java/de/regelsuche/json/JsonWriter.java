package de.regelsuche.json;

import de.regelsuche.retention.RetainedOperation;
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
 *
 * <p>Native work covers input visits, appended/copied code units, nesting
 * transitions and stream writes. Ownership checkpoints observe the actual
 * StringBuilder content at operation/chunk boundaries; its backing arrays
 * remain JDK-internal storage, not separately declared writer allocations.</p>
 */
public final class JsonWriter implements de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(builder);v.reference(firstEntry);v.reference(sink);}
    private final StringBuilder builder = new StringBuilder();
    private final Deque<Boolean> firstEntry = new ArrayDeque<>();
    private static final int STREAM_BUFFER_SIZE = 8192;
    private final Writer sink;

    public JsonWriter() {
        sink = null;
        observeWorkspace();
    }

    /**
     * Writes in bounded chunks instead of retaining the complete document.
     * Call {@link #flush()} after the last value. The caller owns the sink:
     * this class never flushes or closes it. I/O failures are propagated as
     * {@link UncheckedIOException} so nested rendering callbacks stay usable.
     */
    public JsonWriter(Writer sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
        observeWorkspace();
    }

    private void observeWorkspace() {
        try (var workspace = RetainedOperation.retainCompleted(3, this)) { }
    }

    /** Writes the remaining chunk without flushing or closing the caller's sink. */
    public void flush() {
        if (sink == null || builder.isEmpty()) return;
        try (var owner = RetainedOperation.retain(this)) {
            try {
                String chunk = builder.toString();
                try (var output = RetainedOperation.retainCompleted(chunk.length() + 1L, chunk)) {
                    try {
                        worked(chunk.length() + 1L);
                        try {
                            sink.write(chunk);
                        } catch (IOException failure) {
                            var wrapped = new UncheckedIOException(failure);
                            observeFailure(wrapped);
                            throw wrapped;
                        } catch (RuntimeException | Error failure) {
                            observeFailure(failure);
                            throw failure;
                        }
                        // The callback may have mutated its own audited state. Keep the
                        // actual chunk and populated source buffer until that is observed.
                        RetainedOperation.checkpoint();
                        builder.setLength(0);
                        worked(1);
                    } catch (RuntimeException | Error failure) {
                        closeAfterFailure(output, failure);
                        throw failure;
                    }
                }
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    private void drainIfNeeded() {
        worked(1);
        if (sink != null && builder.length() >= STREAM_BUFFER_SIZE) flush();
    }

    public JsonWriter beginObject() {
        try (var owner = RetainedOperation.retain(this)) {
            try {
                drainIfNeeded();
                append('{');
                firstEntry.push(true);
                worked(1);
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter endObject() {
        if (firstEntry.isEmpty()) {
            throw new IllegalStateException("No open object");
        }
        try (var owner = RetainedOperation.retain(this)) {
            try {
                firstEntry.pop();
                worked(1);
                drainIfNeeded();
                append('}');
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter beginArray() {
        try (var owner = RetainedOperation.retain(this)) {
            try {
                drainIfNeeded();
                append('[');
                firstEntry.push(true);
                worked(1);
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter endArray() {
        if (firstEntry.isEmpty()) {
            throw new IllegalStateException("No open array");
        }
        try (var owner = RetainedOperation.retain(this)) {
            try {
                firstEntry.pop();
                worked(1);
                drainIfNeeded();
                append(']');
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter object(String key, Consumer<JsonWriter> body) {
        try (var owner = RetainedOperation.retain(this, key)) {
            try {
                comma();
                appendKey(key);
                beginObject();
                invokeBody(body);
                return endObject();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter array(String key, Consumer<JsonWriter> body) {
        try (var owner = RetainedOperation.retain(this, key)) {
            try {
                comma();
                appendKey(key);
                beginArray();
                invokeBody(body);
                return endArray();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter property(String key, String value) {
        try (var owner = RetainedOperation.retain(this, key, value)) {
            try {
                comma();
                appendKey(key);
                appendString(value);
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter property(String key, int value) {
        try (var owner = RetainedOperation.retain(this, key)) {
            try {
                comma();
                appendKey(key);
                appendNumber(Integer.toString(value));
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter property(String key, long value) {
        try (var owner = RetainedOperation.retain(this, key)) {
            try {
                comma();
                appendKey(key);
                appendNumber(Long.toString(value));
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    /** Emits an exact JSON integer beyond the long range without changing existing overload resolution. */
    public JsonWriter integerProperty(String key, java.math.BigInteger value) {
        Objects.requireNonNull(value, "value");
        try (var owner = RetainedOperation.retain(this, key, value)) {
            try {
                comma();
                appendKey(key);
                appendNumber(value.toString());
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter property(String key, double value) {
        try (var owner = RetainedOperation.retain(this, key)) {
            try {
                comma();
                appendKey(key);
                appendNumber(Double.toString(value));
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter property(String key, boolean value) {
        try (var owner = RetainedOperation.retain(this, key)) {
            try {
                comma();
                appendKey(key);
                append(value ? "true" : "false");
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter nullProperty(String key) {
        try (var owner = RetainedOperation.retain(this, key)) {
            try {
                comma();
                appendKey(key);
                append("null");
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter stringArray(String key, List<String> values) {
        try (var owner = RetainedOperation.retain(this, key, values)) {
            try {
                return array(key, writer -> values.forEach(writer::value));
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter booleanArray(String key, List<Boolean> values) {
        try (var owner = RetainedOperation.retain(this, key, values)) {
            try {
                return array(key, writer -> values.forEach(writer::value));
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter value(String value) {
        try (var owner = RetainedOperation.retain(this, value)) {
            try {
                comma();
                appendString(value);
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter value(int value) {
        try (var owner = RetainedOperation.retain(this)) {
            try {
                comma();
                appendNumber(Integer.toString(value));
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    /** Emit an explicitly numeric value inside an array context. */
    public JsonWriter numberValue(int value) {
        return value(value);
    }

    public JsonWriter value(boolean value) {
        try (var owner = RetainedOperation.retain(this)) {
            try {
                comma();
                append(value ? "true" : "false");
                return observed();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    public JsonWriter objectValue(Consumer<JsonWriter> body) {
        try (var owner = RetainedOperation.retain(this)) {
            try {
                comma();
                beginObject();
                invokeBody(body);
                return endObject();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    /** Emit a nested array value inside an array context. */
    public JsonWriter arrayValue(Consumer<JsonWriter> body) {
        try (var owner = RetainedOperation.retain(this)) {
            try {
                comma();
                beginArray();
                invokeBody(body);
                return endArray();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }

    private void comma() {
        drainIfNeeded();
        worked(1);
        if (firstEntry.isEmpty()) {
            return;
        }
        boolean first = firstEntry.pop();
        worked(1);
        if (!first) {
            append(',');
        }
        firstEntry.push(false);
        worked(1);
    }

    private void appendKey(String key) {
        appendString(key);
        append(':');
    }

    private void appendString(String value) {
        if (value == null) {
            append("null");
            return;
        }
        append('"');
        for (int i = 0; i < value.length(); i++) {
            drainIfNeeded();
            char current = value.charAt(i);
            worked(1);
            switch (current) {
                case '\\' -> append("\\\\");
                case '"' -> append("\\\"");
                case '\n' -> append("\\n");
                case '\r' -> append("\\r");
                case '\t' -> append("\\t");
                case '\b' -> append("\\b");
                case '\f' -> append("\\f");
                default -> {
                    if (current < 0x20) {
                        append("\\u00");
                        append(Character.forDigit(current >>> 4, 16));
                        append(Character.forDigit(current & 15, 16));
                    } else {
                        append(current);
                    }
                }
            }
        }
        append('"');
    }

    private void append(char value) {
        builder.append(value);
        worked(1);
    }

    private void append(String value) {
        builder.append(value);
        worked(value.length());
    }

    private void appendNumber(String value) {
        // StringBuilder.append(Object) historically accepts a null toString result.
        if (value == null) {
            append("null");
            return;
        }
        try (var number = RetainedOperation.retainCompleted(value.length() + 1L, value)) {
            try {
                append(value);
                RetainedOperation.checkpoint();
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(number, failure);
                throw failure;
            }
        }
    }

    private void invokeBody(Consumer<JsonWriter> body) {
        worked(1);
        try {
            body.accept(this);
        } catch (RuntimeException | Error failure) {
            observeFailure(failure);
            throw failure;
        }
    }

    private JsonWriter observed() {
        RetainedOperation.checkpoint();
        return this;
    }

    /** Appends mutate an already retained writer; a failed debit still sees its current content. */
    private static void worked(long units) {
        try {
            RetainedOperation.work(units);
        } catch (RuntimeException | Error failure) {
            observeFailure(failure);
            throw failure;
        }
    }

    private static void observeFailure(Throwable failure) {
        try { RetainedOperation.checkpoint(); }
        catch (RuntimeException | Error observation) {
            if (observation != failure) failure.addSuppressed(observation);
        }
    }

    /** Close while the primary is known; an idempotent automatic close cannot self-suppress it. */
    private static void closeAfterFailure(RetainedOperation.Frame frame, Throwable failure) {
        if (frame == null) return;
        try { frame.close(); }
        catch (RuntimeException | Error cleanup) {
            if (cleanup != failure) failure.addSuppressed(cleanup);
        }
    }

    @Override
    public String toString() {
        if (sink != null) throw new IllegalStateException("Streaming JsonWriter does not retain its output");
        try (var owner = RetainedOperation.retain(this)) {
            try {
                String result = builder.toString();
                try (var output = RetainedOperation.retainCompleted(result.length() + 1L, result)) {
                    try {
                        return result;
                    } catch (RuntimeException | Error failure) {
                        closeAfterFailure(output, failure);
                        throw failure;
                    }
                }
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(owner, failure);
                throw failure;
            }
        }
    }
}
