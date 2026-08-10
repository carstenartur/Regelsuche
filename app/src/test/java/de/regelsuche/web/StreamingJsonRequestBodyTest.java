package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StreamingJsonRequestBodyTest {

    @Test
    void decodesTypedFieldsDirectlyAndSkipsUnknownSubtrees() throws IOException {
        byte[] json = """
            {
              "expression":"x + ä + 😀",
              "maxDepth":7,
              "ignored":{"large":[1,2,3,{"nested":true}]},
              "tags":["a",null,"b"]
            }
            """.getBytes(StandardCharsets.UTF_8);
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(1024);

        Request request = reader.readObject(
            new SmallChunkInputStream(json, 1),
            object -> {
                String expression = "";
                int maxDepth = 4;
                List<String> tags = List.of();
                while (object.nextField()) {
                    switch (object.fieldName()) {
                        case "expression" -> expression = object.readNullableString();
                        case "maxDepth" -> maxDepth = object.readNullableInt();
                        case "tags" -> tags = object.readStringArray();
                        default -> object.skipValue();
                    }
                }
                return new Request(expression, maxDepth, tags);
            }
        );

        assertEquals(new Request("x + ä + 😀", 7, List.of("a", "b")), request);
    }

    @Test
    void nestedTypedObjectsAndStringMapsAvoidGenericTrees() throws IOException {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(512);
        NestedRequest request = reader.readObject(new ByteArrayInputStream(
            """
                {
                  "context":{
                    "goalExpression":"x^2",
                    "outcomes":{"candidate-a":"APPLIED","ignored":null},
                    "unknown":{"deep":[1,2,3]}
                  }
                }
                """.getBytes(StandardCharsets.UTF_8)),
            root -> {
                NestedRequest result = null;
                while (root.nextField()) {
                    if (!root.fieldName().equals("context")) {
                        root.skipValue();
                        continue;
                    }
                    result = root.readNullableObject(context -> {
                        String goal = "";
                        Map<String, String> outcomes = Map.of();
                        while (context.nextField()) {
                            switch (context.fieldName()) {
                                case "goalExpression" ->
                                    goal = context.readNullableString();
                                case "outcomes" -> outcomes = context.readStringMap();
                                default -> context.skipValue();
                            }
                        }
                        return new NestedRequest(goal, outcomes);
                    });
                }
                return result;
            });

        assertEquals(
            new NestedRequest("x^2", Map.of("candidate-a", "APPLIED")),
            request
        );
    }

    @Test
    void heterogeneousTypedArraysStreamStringsAndObjectsWithoutMaps()
            throws IOException {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(1024);
        List<AssumptionValue> values = reader.readObject(
            new ByteArrayInputStream("""
                {
                  "assumptions":[
                    "x != 0",
                    null,
                    {"kind":"POSITIVE","expression":"y > 0","ignored":[1,2]}
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8)),
            root -> {
                List<AssumptionValue> assumptions = List.of();
                while (root.nextField()) {
                    if (!root.fieldName().equals("assumptions")) {
                        root.skipValue();
                        continue;
                    }
                    assumptions = root.readStringOrObjectArray(
                        value -> new AssumptionValue("CUSTOM", value),
                        object -> {
                            String kind = "CUSTOM";
                            String expression = "";
                            while (object.nextField()) {
                                switch (object.fieldName()) {
                                    case "kind" -> kind = object.readNullableString();
                                    case "expression" ->
                                        expression = object.readNullableString();
                                    default -> object.skipValue();
                                }
                            }
                            return new AssumptionValue(kind, expression);
                        }
                    );
                }
                return assumptions;
            }
        );

        assertEquals(List.of(
            new AssumptionValue("CUSTOM", "x != 0"),
            new AssumptionValue("POSITIVE", "y > 0")
        ), values);
    }

    @Test
    void emptyAndWhitespaceBodiesRemainCompatibleWithOptionalObjects()
            throws IOException {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(64);
        assertEquals(Map.of(), reader.readObject(new ByteArrayInputStream(new byte[0])));
        assertEquals(Map.of(), reader.readObject(new ByteArrayInputStream(
            " \n\t ".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void genericCompatibilityTreeIsImmutable() throws IOException {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(256);
        Map<String, Object> value = reader.readObject(new ByteArrayInputStream(
            "{\"a\":[1,{\"b\":true}]}".getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, ((List<?>) value.get("a")).get(0));
        assertThrows(UnsupportedOperationException.class,
            () -> value.put("other", true));
        assertThrows(UnsupportedOperationException.class,
            () -> ((List<?>) value.get("a")).remove(0));
    }

    @Test
    void preservesExactLargeIntegerAndDecimalValues() throws IOException {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(512);
        Map<String, Object> value = reader.readObject(new ByteArrayInputStream(
            "{\"integer\":123456789012345678901234567890,\"decimal\":0.12345678901234567890}"
                .getBytes(StandardCharsets.UTF_8)));

        assertEquals(
            new BigInteger("123456789012345678901234567890"),
            value.get("integer")
        );
        assertEquals(
            new BigDecimal("0.12345678901234567890"),
            value.get("decimal")
        );
    }

    @Test
    void immutableCompatibilityTreeRetainsExplicitNullValues() throws IOException {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(128);
        Map<String, Object> value = reader.readObject(new ByteArrayInputStream(
            "{\"present\":null}".getBytes(StandardCharsets.UTF_8)));

        assertTrue(value.containsKey("present"));
        assertNull(value.get("present"));
    }

    @Test
    void duplicateKeysFailClosedAtEveryObjectDepth() {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(256);
        assertMalformed(reader, "{\"a\":1,\"a\":2}");
        assertMalformed(reader, "{\"nested\":{\"a\":1,\"a\":2}}");

        assertThrows(
            StreamingJsonRequestBody.MalformedJsonRequestException.class,
            () -> reader.readObject(
                new ByteArrayInputStream(
                    "{\"known\":1,\"ignored\":{\"a\":1,\"a\":2}}"
                        .getBytes(StandardCharsets.UTF_8)),
                object -> {
                    while (object.nextField()) {
                        object.skipValue();
                    }
                    return null;
                }
            )
        );
    }

    @Test
    void validDocumentAtTheExactByteBoundaryRemainsAccepted()
            throws IOException {
        byte[] json = "{\"value\":\"boundary\"}"
            .getBytes(StandardCharsets.UTF_8);
        StreamingJsonRequestBody reader =
            new StreamingJsonRequestBody(json.length);

        assertEquals(
            Map.of("value", "boundary"),
            reader.readObject(new SmallChunkInputStream(json, 2))
        );
    }

    @Test
    void trailingRootValuesAndNonObjectRootsAreRejected() {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(256);
        assertMalformed(reader, "{\"a\":1} {\"b\":2}");
        assertMalformed(reader, "[1,2,3]");
        assertMalformed(reader, "true");
    }

    @Test
    void malformedUtf8IsRejectedInsteadOfBeingReplaced() {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(256);
        byte[] prefix = "{\"value\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, malformed, 0, prefix.length);
        malformed[prefix.length] = (byte) 0xC3;
        malformed[prefix.length + 1] = 0x28;
        System.arraycopy(suffix, 0, malformed, prefix.length + 2, suffix.length);

        assertThrows(
            StreamingJsonRequestBody.MalformedJsonRequestException.class,
            () -> reader.readObject(new ByteArrayInputStream(malformed))
        );
    }

    @Test
    void byteLimitRemainsTheAuthoritative413Signal() {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(32);
        byte[] oversized = ("{\"value\":\"" + "x".repeat(64) + "\"}")
            .getBytes(StandardCharsets.UTF_8);

        BoundedRequestBody.PayloadTooLargeException exception = assertThrows(
            BoundedRequestBody.PayloadTooLargeException.class,
            () -> reader.readObject(new SmallChunkInputStream(oversized, 3))
        );
        assertEquals(32, exception.limitBytes());
    }

    @Test
    void scalarTypeMismatchesAndPartialDecodersFailClosed() {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(256);
        assertThrows(
            StreamingJsonRequestBody.MalformedJsonRequestException.class,
            () -> reader.readObject(
                new ByteArrayInputStream("{\"count\":\"7\"}"
                    .getBytes(StandardCharsets.UTF_8)),
                object -> {
                    object.nextField();
                    return object.readNullableInt();
                })
        );

        StreamingJsonRequestBody.MalformedJsonRequestException partial = assertThrows(
            StreamingJsonRequestBody.MalformedJsonRequestException.class,
            () -> reader.readObject(
                new ByteArrayInputStream("{\"a\":1,\"b\":2}"
                    .getBytes(StandardCharsets.UTF_8)),
                object -> {
                    object.nextField();
                    object.readValue();
                    return "stopped";
                })
        );
        assertTrue(partial.getMessage().contains("stopped before"));
    }

    @Test
    void nestingConstraintRejectsAdversarialDocuments() {
        StreamingJsonRequestBody reader = new StreamingJsonRequestBody(4096);
        String nested = "{\"value\":" + "[".repeat(140) + "0"
            + "]".repeat(140) + "}";
        assertMalformed(reader, nested);
    }

    private void assertMalformed(StreamingJsonRequestBody reader, String json) {
        StreamingJsonRequestBody.MalformedJsonRequestException exception =
            assertThrows(
                StreamingJsonRequestBody.MalformedJsonRequestException.class,
                () -> reader.readObject(new ByteArrayInputStream(
                    json.getBytes(StandardCharsets.UTF_8)))
            );
        assertInstanceOf(IOException.class, exception);
    }

    private record Request(String expression, int maxDepth, List<String> tags) {
    }

    private record NestedRequest(
        String goalExpression,
        Map<String, String> outcomes
    ) {
    }

    private record AssumptionValue(String kind, String expression) {
    }

    private static final class SmallChunkInputStream extends InputStream {
        private final ByteArrayInputStream delegate;
        private final int maximumChunk;

        private SmallChunkInputStream(byte[] source, int maximumChunk) {
            this.delegate = new ByteArrayInputStream(source);
            this.maximumChunk = maximumChunk;
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, Math.min(length, maximumChunk));
        }
    }
}
