package de.regelsuche.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Strict bounded reader for the two public derivatives of a private held-out
 * reveal bundle.
 *
 * <p>Deserialization invokes the domain record constructors, so all canonical
 * ordering, split policy and content hashes are recomputed before an artifact
 * is accepted. Concrete held-out expressions are neither required nor exposed.
 * </p>
 */
public final class EvolutionRewriteProgramHeldOutPublicCodec {
    static final long MAX_PUBLIC_ARTIFACT_BYTES = 1_048_576L;

    private static final ObjectMapper JSON = new ObjectMapper()
        .findAndRegisterModules()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public EvolutionRewriteProgramHeldOutCommitment readCommitment(Path input) {
        return read(
            input,
            EvolutionRewriteProgramHeldOutCommitment.class,
            "held-out commitment");
    }

    public EvolutionRewriteProgramHeldOutSplitReferences readSplitReferences(
        Path input
    ) {
        return read(
            input,
            EvolutionRewriteProgramHeldOutSplitReferences.class,
            "held-out split references");
    }

    public EvolutionRewriteProgramHeldOutCommitment readCommitment(
        String canonicalJson
    ) {
        return read(
            canonicalJson,
            EvolutionRewriteProgramHeldOutCommitment.class,
            "held-out commitment");
    }

    public EvolutionRewriteProgramHeldOutSplitReferences readSplitReferences(
        String canonicalJson
    ) {
        return read(
            canonicalJson,
            EvolutionRewriteProgramHeldOutSplitReferences.class,
            "held-out split references");
    }

    private static <T> T read(Path input, Class<T> type, String label) {
        Path normalized = Objects.requireNonNull(input, "input")
            .toAbsolutePath()
            .normalize();
        try {
            if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                    label + " is not a regular non-symlink file");
            }
            long size = Files.size(normalized);
            if (size < 1 || size > MAX_PUBLIC_ARTIFACT_BYTES) {
                throw new IllegalArgumentException(
                    label + " size is outside the accepted bound");
            }
            byte[] bytes = Files.readAllBytes(normalized);
            return read(decodeUtf8(bytes, label), type, label);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "unable to read " + label, exception);
        }
    }

    private static <T> T read(String json, Class<T> type, String label) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(label + " JSON must not be blank");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length
                > MAX_PUBLIC_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                label + " JSON exceeds the accepted bound");
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid " + label + " JSON", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes, String label) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                label + " is not valid UTF-8", exception);
        }
    }
}
