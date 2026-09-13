package de.regelsuche.symbol;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Bounded, strict data interchange. An imported document is not a proof or authorization. */
public final class SymbolicExpressionCodec {
    public static final String SCHEMA = "regelsuche.symbolic-expression/v1";
    public static final int MAXIMUM_BYTES = 1_048_576;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
            .maxStringLength(SymbolicExpression.MAXIMUM_SOURCE_LENGTH).maxNameLength(128).maxNumberLength(32).build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public byte[] encode(SymbolicExpression document) {
        Objects.requireNonNull(document, "document");
        var root = MAPPER.createObjectNode().put("schema", SCHEMA).put("source", document.original().source());
        var bindings = root.putArray("bindings");
        new TreeMap<>(document.sourceBindings()).forEach((name, id) -> bindings.addObject()
            .put("name", name).put("id", id.canonicalText()));
        var labels = root.putArray("displayNames");
        document.displayNames().entrySet().stream().sorted(Comparator.comparing(entry -> entry.getKey().canonicalText()))
            .forEach(entry -> labels.addObject().put("id", entry.getKey().canonicalText()).put("label", entry.getValue()));
        try {
            byte[] bytes = MAPPER.writeValueAsBytes(root);
            if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("symbolic document is too large");
            return bytes;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot encode symbolic document", exception);
        }
    }

    public SymbolicExpression decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAXIMUM_BYTES) throw new IllegalArgumentException("symbolic document is too large");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode root = MAPPER.readTree(text);
            requireFields(root, Set.of("schema", "source", "bindings", "displayNames"));
            if (!SCHEMA.equals(text(root, "schema"))) throw new IllegalArgumentException("unsupported symbolic document schema");
            return SymbolicExpression.fromBindings(text(root, "source"), bindings(root.get("bindings")), labels(root.get("displayNames")));
        } catch (JsonProcessingException | CharacterCodingException exception) {
            throw new IllegalArgumentException("invalid UTF-8 symbolic JSON document", exception);
        }
    }

    private static Map<String, SymbolId> bindings(JsonNode array) {
        requireArray(array);
        Map<String, SymbolId> bindings = new HashMap<>();
        for (JsonNode entry : array) {
            requireFields(entry, Set.of("name", "id"));
            String name = SymbolScope.requireName(text(entry, "name"));
            var id = SymbolId.fromCanonicalText(text(entry, "id"));
            if (bindings.putIfAbsent(name, id) != null) throw new IllegalArgumentException("duplicate source binding");
        }
        return bindings;
    }

    private static Map<SymbolId, String> labels(JsonNode array) {
        requireArray(array);
        Map<SymbolId, String> labels = new HashMap<>();
        for (JsonNode entry : array) {
            requireFields(entry, Set.of("id", "label"));
            var id = SymbolId.fromCanonicalText(text(entry, "id"));
            String label = SymbolScope.requireName(text(entry, "label"));
            if (labels.putIfAbsent(id, label) != null) throw new IllegalArgumentException("duplicate symbol label");
        }
        return labels;
    }

    private static void requireArray(JsonNode node) {
        if (node == null || !node.isArray() || node.size() > SymbolicExpression.MAXIMUM_BINDINGS) {
            throw new IllegalArgumentException("expected bounded symbol array");
        }
    }

    private static void requireFields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) {
            throw new IllegalArgumentException("symbolic document has missing or unknown fields");
        }
        Set<String> actual = new HashSet<>();
        for (var entry : node.properties()) actual.add(entry.getKey());
        if (!actual.equals(expected)) throw new IllegalArgumentException("symbolic document has unexpected fields");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("expected string field: " + field);
        return value.textValue();
    }
}
