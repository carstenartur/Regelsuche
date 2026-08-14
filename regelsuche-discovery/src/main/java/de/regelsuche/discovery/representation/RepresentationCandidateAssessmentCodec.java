package de.regelsuche.discovery.representation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Strict deterministic JSON codec for representation-candidate evidence. */
public final class RepresentationCandidateAssessmentCodec {
    private static final ObjectMapper MAPPER = JsonMapper.builder(
            JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build())
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public String encode(RepresentationCandidateAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        try {
            return MAPPER.writeValueAsString(assessment) + "\n";
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                "cannot encode representation-candidate assessment",
                exception
            );
        }
    }

    public RepresentationCandidateAssessment decode(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return MAPPER.readValue(
                json,
                RepresentationCandidateAssessment.class
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                "cannot decode representation-candidate assessment",
                exception
            );
        }
    }

    public String semanticHash(RepresentationCandidateAssessment assessment) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(
                digest.digest(encode(assessment).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
