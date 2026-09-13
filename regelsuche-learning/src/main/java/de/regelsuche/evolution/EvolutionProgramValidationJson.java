package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.TreeMap;

/** Strict additive codec; the frozen v1 mapper and hash material stay untouched. */
final class EvolutionProgramValidationJson {
    private static final ObjectMapper JSON = EvolutionValidationArtifactSupport.JSON.copy()
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private EvolutionProgramValidationJson() { }

    static Map<String, Object> material(String schema, Object... fields) {
        Map<String, Object> result = new TreeMap<>();
        result.put("schema", schema);
        for (int index = 0; index < fields.length; index += 2) {
            result.put((String) fields[index], fields[index + 1]);
        }
        return result;
    }

    static String hash(Object value) { return EvolutionValidationArtifactSupport.hash(value); }

    static void requireHash(String actual, Object material) {
        EvolutionGenome.requireSha256(actual, "contentHash");
        if (!hash(material).equals(actual)) {
            throw new IllegalArgumentException("program VALIDATION contentHash mismatch");
        }
    }

    static String write(Object value) {
        try { return JSON.writeValueAsString(value) + "\n"; }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize program VALIDATION artifact", exception);
        }
    }

    static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("program VALIDATION JSON must not be blank");
        }
        try { return JSON.readValue(json, type); }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("invalid program VALIDATION artifact", exception);
        }
    }
}
