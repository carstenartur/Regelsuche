package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class EvolutionValidationArtifactSupport {
    static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build())
        .findAndRegisterModules()
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private EvolutionValidationArtifactSupport() {
    }

    static String configurationHash(
        String genomeHash,
        String alphaStructuralHash,
        EvolutionValidationSearchConfiguration configuration
    ) {
        EvolutionGenome.requireSha256(genomeHash, "genomeHash");
        EvolutionGenome.requireSha256(
            alphaStructuralHash, "alphaStructuralHash");
        Objects.requireNonNull(configuration, "searchConfiguration");
        Map<String, Object> value = new TreeMap<>();
        value.put("alphaStructuralHash", alphaStructuralHash);
        value.put("genomeHash", genomeHash);
        value.put("searchConfiguration", configuration.canonicalMaterial());
        return hash(value);
    }

    static String hash(Object value) {
        try {
            return EvolutionGenome.hash(JSON.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                "cannot hash evolution validation artifact", exception);
        }
    }

    static List<String> canonicalStrings(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> retained = new ArrayList<>();
        for (String value : values) {
            retained.add(requireText(value, field));
        }
        return retained.stream().distinct().sorted().toList();
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
