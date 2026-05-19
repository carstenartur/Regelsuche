package de.regelsuche.json;

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

    public JsonWriter beginObject() {
        builder.append('{');
        firstEntry.push(true);
        return this;
    }

    public JsonWriter endObject() {
        if (firstEntry.isEmpty()) {
            throw new IllegalStateException("No open object");
        }
        firstEntry.pop();
        builder.append('}');
        return this;
    }

    public JsonWriter beginArray() {
        builder.append('[');
        firstEntry.push(true);
        return this;
    }

    public JsonWriter endArray() {
        if (firstEntry.isEmpty()) {
            throw new IllegalStateException("No open array");
        }
        firstEntry.pop();
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

    public JsonWriter objectValue(Consumer<JsonWriter> body) {
        comma();
        beginObject();
        body.accept(this);
        return endObject();
    }

    private void comma() {
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
        return builder.toString();
    }
}
