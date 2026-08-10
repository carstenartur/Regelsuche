package de.regelsuche.web;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strict streaming decoder for untrusted Workbench JSON request bodies.
 *
 * <p>The decoder consumes the byte-limited request stream directly with
 * Jackson Core. It therefore avoids an intermediate complete {@code byte[]}
 * and {@code String}. Duplicate object keys, malformed UTF-8, non-object roots,
 * trailing root values and configured structural limits fail closed.</p>
 *
 * <p>HTTP handlers can decode fields directly into a typed request record via
 * {@link #readObject(HttpExchange, ObjectDecoder)}. The compatibility overload
 * returning a map remains available for routes that have not yet migrated, but
 * it still streams transport and parsing rather than buffering the raw body.</p>
 */
public final class StreamingJsonRequestBody {
    private static final int MAX_NESTING_DEPTH = 128;
    private static final int MAX_PROPERTY_NAME_LENGTH = 4096;
    private static final int MAX_NUMBER_LENGTH = 1024;

    private final int maxBytes;
    private final JsonFactory jsonFactory;

    public StreamingJsonRequestBody(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
            .maxDocumentLength(maxBytes)
            .maxTokenCount(Math.max(1024L, (long) maxBytes * 2L))
            .maxNestingDepth(MAX_NESTING_DEPTH)
            .maxNumberLength(Math.min(MAX_NUMBER_LENGTH, maxBytes))
            .maxStringLength(maxBytes)
            .maxNameLength(Math.min(MAX_PROPERTY_NAME_LENGTH, maxBytes))
            .build();
        this.jsonFactory = JsonFactory.builder()
            .streamReadConstraints(constraints)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();
    }

    /**
     * Reads one complete JSON object into an immutable generic tree.
     *
     * <p>Prefer the typed decoder overload for HTTP handlers. An empty or
     * whitespace-only body is represented as an empty map for compatibility
     * with existing optional request bodies.</p>
     */
    public Map<String, Object> readObject(HttpExchange exchange) throws IOException {
        return readObject(exchange, ObjectCursor::readRemainingObject);
    }

    /**
     * Streams one complete JSON object into a route-specific typed decoder.
     * Unknown fields can be discarded without materializing them through
     * {@link ObjectCursor#skipValue()}.
     */
    public <T> T readObject(HttpExchange exchange, ObjectDecoder<T> decoder)
            throws IOException {
        Objects.requireNonNull(exchange, "exchange");
        try (InputStream input = BoundedRequestBody.open(exchange, maxBytes)) {
            return decodeBounded(input, decoder);
        }
    }

    /**
     * Decodes a non-HTTP stream with the same byte and JSON limits.
     * Primarily useful for focused adapter tests and checkout-owned tools.
     */
    public Map<String, Object> readObject(InputStream input) throws IOException {
        return readObject(input, ObjectCursor::readRemainingObject);
    }

    /**
     * Decodes a non-HTTP stream directly into a typed object while retaining
     * the same byte and JSON limits as the HTTP overload.
     */
    public <T> T readObject(InputStream input, ObjectDecoder<T> decoder)
            throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(decoder, "decoder");
        try (InputStream bounded = BoundedRequestBody.open(input, maxBytes)) {
            return decodeBounded(bounded, decoder);
        }
    }

    private <T> T decodeBounded(InputStream bounded, ObjectDecoder<T> decoder)
            throws IOException {
        Objects.requireNonNull(decoder, "decoder");
        try (JsonParser parser = jsonFactory.createParser(bounded)) {
            JsonToken first = parser.nextToken();
            ObjectCursor cursor;
            if (first == null) {
                cursor = ObjectCursor.empty();
            } else if (first == JsonToken.START_OBJECT) {
                cursor = new ObjectCursor(parser);
            } else {
                throw malformed("JSON request body must contain one object", null);
            }

            T result = decoder.decode(cursor);
            cursor.requireFinished();
            if (first != null && parser.nextToken() != null) {
                throw malformed("JSON request body contains trailing content", null);
            }
            return result;
        } catch (BoundedRequestBody.PayloadTooLargeException exception) {
            throw exception;
        } catch (MalformedJsonRequestException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            BoundedRequestBody.PayloadTooLargeException oversized =
                findCause(exception, BoundedRequestBody.PayloadTooLargeException.class);
            if (oversized != null) {
                throw oversized;
            }
            throw malformed("invalid JSON request body", exception);
        }
    }

    /** Decodes one top-level object without exposing Jackson to route code. */
    @FunctionalInterface
    public interface ObjectDecoder<T> {
        T decode(ObjectCursor object) throws IOException;
    }

    /** Decodes one string element inside a heterogeneous typed array. */
    @FunctionalInterface
    public interface StringDecoder<T> {
        T decode(String value) throws IOException;
    }

    /**
     * Forward-only cursor over one JSON object.
     *
     * <p>A decoder must consume or skip every announced field and iterate until
     * {@link #nextField()} returns {@code false}. This makes accidental partial
     * parsing fail closed.</p>
     */
    public static final class ObjectCursor {
        private final JsonParser parser;
        private boolean finished;
        private boolean valuePending;
        private String fieldName;
        private JsonToken valueToken;

        private ObjectCursor(JsonParser parser) {
            this.parser = Objects.requireNonNull(parser, "parser");
        }

        private ObjectCursor() {
            this.parser = null;
            this.finished = true;
        }

        private static ObjectCursor empty() {
            return new ObjectCursor();
        }

        /** Advances to the next field. */
        public boolean nextField() throws IOException {
            if (valuePending) {
                throw malformed("JSON field '" + fieldName + "' was not consumed", null);
            }
            if (finished) {
                return false;
            }
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_OBJECT) {
                finished = true;
                return false;
            }
            if (token != JsonToken.FIELD_NAME) {
                throw malformed("expected a JSON object field", null);
            }
            fieldName = parser.currentName();
            valueToken = parser.nextToken();
            if (valueToken == null) {
                throw malformed("unexpected end of JSON request body", null);
            }
            valuePending = true;
            return true;
        }

        public String fieldName() {
            if (!valuePending) {
                throw new IllegalStateException("no current JSON field");
            }
            return fieldName;
        }

        /** Reads a string or JSON {@code null}; other token types are rejected. */
        public String readNullableString() throws IOException {
            requirePending();
            JsonToken token = consumeToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token != JsonToken.VALUE_STRING) {
                throw typeMismatch("string", token);
            }
            return parser.getText();
        }

        /** Reads an integral JSON number or {@code null}. */
        public Integer readNullableInt() throws IOException {
            requirePending();
            JsonToken token = consumeToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token != JsonToken.VALUE_NUMBER_INT) {
                throw typeMismatch("integer", token);
            }
            try {
                return parser.getIntValue();
            } catch (JsonProcessingException exception) {
                throw malformed("JSON field '" + fieldName
                    + "' is outside the 32-bit integer range", exception);
            }
        }

        /** Reads a JSON boolean or {@code null}. */
        public Boolean readNullableBoolean() throws IOException {
            requirePending();
            JsonToken token = consumeToken();
            return switch (token) {
                case VALUE_TRUE -> Boolean.TRUE;
                case VALUE_FALSE -> Boolean.FALSE;
                case VALUE_NULL -> null;
                default -> throw typeMismatch("boolean", token);
            };
        }

        /** Reads an array containing only strings and nulls. */
        public List<String> readStringArray() throws IOException {
            requirePending();
            JsonToken token = consumeToken();
            if (token == JsonToken.VALUE_NULL) {
                return List.of();
            }
            if (token != JsonToken.START_ARRAY) {
                throw typeMismatch("array of strings", token);
            }
            List<String> values = new ArrayList<>();
            while (true) {
                JsonToken item = parser.nextToken();
                if (item == JsonToken.END_ARRAY) {
                    return List.copyOf(values);
                }
                if (item == null) {
                    throw malformed("unexpected end of JSON request body", null);
                }
                if (item == JsonToken.VALUE_NULL) {
                    continue;
                }
                if (item != JsonToken.VALUE_STRING) {
                    throw malformed("JSON field '" + fieldName
                        + "' must contain only strings", null);
                }
                values.add(parser.getText());
            }
        }

        /**
         * Reads an array whose non-null elements are either strings or
         * objects. Each object is consumed directly through the supplied
         * typed decoder; no generic map or intermediate JSON tree is built.
         */
        public <T> List<T> readStringOrObjectArray(
            StringDecoder<T> stringDecoder,
            ObjectDecoder<T> objectDecoder
        ) throws IOException {
            Objects.requireNonNull(stringDecoder, "stringDecoder");
            Objects.requireNonNull(objectDecoder, "objectDecoder");
            requirePending();
            JsonToken token = consumeToken();
            if (token == JsonToken.VALUE_NULL) {
                return List.of();
            }
            if (token != JsonToken.START_ARRAY) {
                throw typeMismatch("array of strings or objects", token);
            }
            List<T> values = new ArrayList<>();
            while (true) {
                JsonToken item = parser.nextToken();
                if (item == JsonToken.END_ARRAY) {
                    return List.copyOf(values);
                }
                if (item == null) {
                    throw malformed("unexpected end of JSON request body", null);
                }
                if (item == JsonToken.VALUE_NULL) {
                    continue;
                }
                T decoded;
                if (item == JsonToken.VALUE_STRING) {
                    decoded = stringDecoder.decode(parser.getText());
                } else if (item == JsonToken.START_OBJECT) {
                    ObjectCursor nested = new ObjectCursor(parser);
                    decoded = objectDecoder.decode(nested);
                    nested.requireFinished();
                } else {
                    throw malformed("JSON field '" + fieldName
                        + "' must contain only strings or objects", null);
                }
                if (decoded == null) {
                    throw malformed("JSON field '" + fieldName
                        + "' decoder returned null", null);
                }
                values.add(decoded);
            }
        }

        /** Reads an object containing only string values and JSON nulls. */
        public Map<String, String> readStringMap() throws IOException {
            requirePending();
            JsonToken token = consumeToken();
            if (token == JsonToken.VALUE_NULL) {
                return Map.of();
            }
            if (token != JsonToken.START_OBJECT) {
                throw typeMismatch("object with string values", token);
            }
            Map<String, String> values = new LinkedHashMap<>();
            while (true) {
                JsonToken entry = parser.nextToken();
                if (entry == JsonToken.END_OBJECT) {
                    return Collections.unmodifiableMap(values);
                }
                if (entry != JsonToken.FIELD_NAME) {
                    throw malformed("expected a JSON object field", null);
                }
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == null) {
                    throw malformed("unexpected end of JSON request body", null);
                }
                if (value == JsonToken.VALUE_NULL) {
                    continue;
                }
                if (value != JsonToken.VALUE_STRING) {
                    throw malformed("JSON field '" + fieldName
                        + "' must contain only string values", null);
                }
                values.put(name, parser.getText());
            }
        }

        /**
         * Reads an object value directly through another typed decoder. JSON
         * {@code null} is returned as {@code null}.
         */
        public <T> T readNullableObject(ObjectDecoder<T> decoder)
                throws IOException {
            Objects.requireNonNull(decoder, "decoder");
            requirePending();
            JsonToken token = consumeToken();
            if (token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token != JsonToken.START_OBJECT) {
                throw typeMismatch("object", token);
            }
            ObjectCursor nested = new ObjectCursor(parser);
            T value = decoder.decode(nested);
            nested.requireFinished();
            return value;
        }

        /** Reads the current value as an immutable JSON-compatible Java tree. */
        public Object readValue() throws IOException {
            requirePending();
            return StreamingJsonRequestBody.readValue(parser, consumeToken());
        }

        /** Discards the current field value without constructing a Java tree. */
        public void skipValue() throws IOException {
            requirePending();
            JsonToken token = consumeToken();
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                parser.skipChildren();
            }
        }

        private Map<String, Object> readRemainingObject() throws IOException {
            Map<String, Object> values = new LinkedHashMap<>();
            while (nextField()) {
                String name = fieldName();
                values.put(name, readValue());
            }
            return Collections.unmodifiableMap(values);
        }

        private void requireFinished() throws IOException {
            if (valuePending) {
                throw malformed("JSON field '" + fieldName + "' was not consumed", null);
            }
            if (!finished) {
                throw malformed("JSON request decoder stopped before the object ended", null);
            }
        }

        private void requirePending() {
            if (!valuePending) {
                throw new IllegalStateException("no current JSON field value");
            }
        }

        private JsonToken consumeToken() {
            JsonToken token = valueToken;
            valuePending = false;
            valueToken = null;
            return token;
        }

        private MalformedJsonRequestException typeMismatch(
            String expected,
            JsonToken actual
        ) {
            return malformed("JSON field '" + fieldName + "' must be "
                + expected + ", found " + actual, null);
        }
    }

    private static Object readValue(JsonParser parser, JsonToken token)
            throws IOException {
        return switch (token) {
            case START_OBJECT -> readObjectTree(parser);
            case START_ARRAY -> readArrayTree(parser);
            case VALUE_STRING -> parser.getText();
            case VALUE_NUMBER_INT -> parser.getNumberValue();
            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
            case VALUE_TRUE -> Boolean.TRUE;
            case VALUE_FALSE -> Boolean.FALSE;
            case VALUE_NULL -> null;
            default -> throw malformed("unsupported JSON token " + token, null);
        };
    }

    private static Map<String, Object> readObjectTree(JsonParser parser)
            throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_OBJECT) {
                return Collections.unmodifiableMap(values);
            }
            if (token != JsonToken.FIELD_NAME) {
                throw malformed("expected a JSON object field", null);
            }
            String name = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw malformed("unexpected end of JSON request body", null);
            }
            if (values.containsKey(name)) {
                // Jackson's strict duplicate detector is authoritative. This is
                // a defensive check that also covers future factory changes.
                throw malformed("duplicate JSON object field", null);
            }
            values.put(name, readValue(parser, valueToken));
        }
    }

    private static List<Object> readArrayTree(JsonParser parser)
            throws IOException {
        List<Object> values = new ArrayList<>();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_ARRAY) {
                return Collections.unmodifiableList(values);
            }
            if (token == null) {
                throw malformed("unexpected end of JSON request body", null);
            }
            values.add(readValue(parser, token));
        }
    }

    private static MalformedJsonRequestException malformed(
        String message,
        Throwable cause
    ) {
        return cause == null
            ? new MalformedJsonRequestException(message)
            : new MalformedJsonRequestException(message, cause);
    }

    private static <T extends Throwable> T findCause(
        Throwable throwable,
        Class<T> type
    ) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    /** Checked signal rendered as a stable HTTP 400 response at the boundary. */
    public static final class MalformedJsonRequestException extends IOException {
        private static final long serialVersionUID = 1L;

        public MalformedJsonRequestException(String message) {
            super(message);
        }

        public MalformedJsonRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
