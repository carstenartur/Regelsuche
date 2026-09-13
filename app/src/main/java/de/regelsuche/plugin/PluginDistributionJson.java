package de.regelsuche.plugin;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;

/** Strict JSON boundary shared by the distribution formats and downloaded metadata. */
final class PluginDistributionJson {
    private static final ObjectMapper JSON = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .findAndAddModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .build();

    private PluginDistributionJson() { }

    static <T> T read(byte[] bytes, Class<T> type) {
        try {
            T value = JSON.readValue(bytes, type);
            if (value == null) {
                throw new IllegalArgumentException("distribution JSON must not be null");
            }
            return value;
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("invalid distribution JSON", failure);
        }
    }

    static void validate(byte[] bytes) {
        if (!read(bytes, com.fasterxml.jackson.databind.JsonNode.class).isObject()) {
            throw new IllegalArgumentException("distribution JSON must be an object");
        }
    }

    static String canonical(Object value) {
        try {
            return JSON.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("cannot serialize distribution JSON", failure);
        }
    }

    static String hash(String value) {
        return PluginArtifactVerifier.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
