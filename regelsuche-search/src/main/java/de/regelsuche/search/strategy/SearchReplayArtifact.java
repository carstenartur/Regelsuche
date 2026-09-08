package de.regelsuche.search.strategy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Bounded, exact-byte artifact loading. References and decoded JSON remain observational data. */
public final class SearchReplayArtifact {
    public static final int MAX_BYTES = 32_000_000;
    private SearchReplayArtifact() { }

    public record Reference(String sha256, int byteLength) {
        public Reference {
            if (sha256 == null || !sha256.matches("sha256:[0-9a-f]{64}") || byteLength < 1 || byteLength > MAX_BYTES) {
                throw new IllegalArgumentException("invalid search replay artifact reference");
            }
        }
    }

    public static Reference describe(String canonicalJson) {
        try {
            Objects.requireNonNull(canonicalJson, "canonicalJson");
            if (canonicalJson.length() > MAX_BYTES) throw new IllegalArgumentException("search replay artifact too large");
            var bytes = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(canonicalJson));
            return new Reference(hash(bytes), bytes.limit());
        } catch (CharacterCodingException malformed) { throw new IllegalArgumentException("invalid artifact Unicode", malformed); }
    }

    /** Verify size and digest before any caller-provided solver or replay source can run. */
    public static String load(Path file, Reference expected) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(expected, "expected");
        byte[] bytes;
        try (var stream = Files.newInputStream(file)) { bytes = stream.readNBytes(expected.byteLength() + 1); }
        if (bytes.length != expected.byteLength() || !hash(ByteBuffer.wrap(bytes)).equals(expected.sha256())) {
            throw new IllegalArgumentException("search replay artifact bytes differ from reference");
        }
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException malformed) { throw new IllegalArgumentException("invalid artifact UTF-8", malformed); }
    }

    private static String hash(ByteBuffer bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
