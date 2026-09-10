package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Map;

/** Shared strict canonical JSON support for learned-pattern authorization artifacts. */
final class LearnedPatternAuthorizationJson {
    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private LearnedPatternAuthorizationJson() {
    }

    static <T> T read(String json, Class<T> type, String name) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(name + " JSON must not be blank");
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid " + name + " JSON", exception);
        }
    }

    static String write(Object value) {
        try {
            return JSON.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot serialize learned authorization artifact", exception);
        }
    }

    static String hash(Map<String, ?> payload) {
        return EvolutionGenome.hash(write(payload));
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String requireRevision(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException(
                field + " must be a lowercase commit SHA");
        }
        return value;
    }

    static void requireHash(String value, String field) {
        EvolutionGenome.requireSha256(value, field);
    }
}
