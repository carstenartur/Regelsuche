package de.regelsuche.discovery.representation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Strict canonical codec for immutable representation-discovery workspaces.
 *
 * <p>Decoding rejects duplicate or unknown JSON properties, trailing values,
 * malformed UTF-8, forged nested identities and any semantically equivalent
 * but non-canonical JSON spelling. This gives file and HTTP consumers one
 * fail-closed boundary instead of letting each surface invent a looser parser.</p>
 */
public final class RepresentationDiscoveryRunWorkspaceCodec {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .build();

    public String encode(RepresentationDiscoveryRunWorkspace workspace) {
        return Objects.requireNonNull(workspace, "workspace")
            .toCanonicalJson();
    }

    public byte[] encodeBytes(
        RepresentationDiscoveryRunWorkspace workspace
    ) {
        return encode(workspace).getBytes(StandardCharsets.UTF_8);
    }

    public RepresentationDiscoveryRunWorkspace decode(String source) {
        Objects.requireNonNull(source, "source");
        try {
            RepresentationDiscoveryRunWorkspace workspace = JSON.readValue(
                source,
                RepresentationDiscoveryRunWorkspace.class
            );
            String canonical = encode(workspace);
            if (!canonical.equals(source)) {
                throw new IllegalArgumentException(
                    "run workspace JSON is not canonical");
            }
            return workspace;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                "invalid representation-discovery run workspace JSON",
                exception
            );
        }
    }

    public RepresentationDiscoveryRunWorkspace decode(byte[] source) {
        Objects.requireNonNull(source, "source");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source))
                .toString();
            return decode(text);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "run workspace is not valid UTF-8",
                exception
            );
        }
    }
}
