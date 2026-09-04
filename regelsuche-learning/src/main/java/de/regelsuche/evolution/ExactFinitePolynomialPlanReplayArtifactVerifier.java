package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.json.JsonWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Independently loads and verifies content-addressed bytes for exact finite
 * polynomial replay artifacts.
 *
 * <p>The verifier establishes storage identity and immutable UTF-8 byte
 * snapshots only. Receipt and plan-run roles retain separate schemas and media
 * types. This boundary does not parse fields, rerun the solver, replay
 * primitive rewrites or issue proof or promotion authority.</p>
 */
public final class ExactFinitePolynomialPlanReplayArtifactVerifier {
    public static final String REFERENCE_SCHEMA =
        "regelsuche.exact-finite-polynomial-plan-artifact-reference/v1";
    public static final String VERIFIER_ID =
        "regelsuche.exact-finite-polynomial-plan-artifact-verifier/v2";
    public static final String RECEIPT_ROLE = "replay-receipt";
    public static final String RECEIPT_MEDIA_TYPE =
        "application/vnd.regelsuche.exact-finite-polynomial-plan-replay-receipt+json";
    public static final String PLAN_RUN_ROLE = "plan-run";
    public static final String PLAN_RUN_MEDIA_TYPE =
        "application/vnd.regelsuche.exact-finite-polynomial-plan-run+json";
    public static final int MAX_ARTIFACT_BYTES = 1_000_000;
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            VERIFIER_ID,
            REFERENCE_SCHEMA,
            RECEIPT_ROLE,
            ReplayReceipt.SCHEMA,
            RECEIPT_MEDIA_TYPE,
            PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRun.SCHEMA,
            ExactFinitePolynomialPlanRun.ARTIFACT_REVISION_HASH,
            PLAN_RUN_MEDIA_TYPE,
            Integer.toString(MAX_ARTIFACT_BYTES),
            "load-by-expected-artifact-id",
            "role-schema-media-type-separation",
            "verify-returned-key-length-sha256-and-metadata-id",
            "strict-utf8-no-bom-compact-object-boundary",
            "sealed-verifier-owned-immutable-byte-snapshot"));

    private static final Pattern MEDIA_TYPE = Pattern.compile(
        "[a-z0-9][a-z0-9.+-]{0,63}/[a-z0-9][a-z0-9.+-]{0,127}");
    private static final byte[] UTF8_BOM = {
        (byte) 0xef,
        (byte) 0xbb,
        (byte) 0xbf
    };

    public ArtifactReference describeReceipt(ReplayReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        return ArtifactReference.describe(
            RECEIPT_ROLE,
            ReplayReceipt.SCHEMA,
            RECEIPT_MEDIA_TYPE,
            receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    public ArtifactReference describePlanRun(
        ExactFinitePolynomialPlanRun planRun
    ) {
        Objects.requireNonNull(planRun, "planRun");
        return ArtifactReference.describe(
            PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRun.SCHEMA,
            PLAN_RUN_MEDIA_TYPE,
            planRun.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    public VerifiedArtifactBytes verifyReceipt(
        ArtifactReference expected,
        ArtifactSource source
    ) {
        return verify(
            expected,
            source,
            RECEIPT_ROLE,
            ReplayReceipt.SCHEMA,
            RECEIPT_MEDIA_TYPE);
    }

    public VerifiedArtifactBytes verifyPlanRun(
        ArtifactReference expected,
        ArtifactSource source
    ) {
        return verify(
            expected,
            source,
            PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRun.SCHEMA,
            PLAN_RUN_MEDIA_TYPE);
    }

    private static VerifiedArtifactBytes verify(
        ArtifactReference expected,
        ArtifactSource source,
        String requiredRole,
        String requiredContentSchema,
        String requiredMediaType
    ) {
        requireMetadata(
            expected,
            requiredRole,
            requiredContentSchema,
            requiredMediaType);
        Objects.requireNonNull(source, "source");
        LoadedArtifact loaded = Objects.requireNonNull(
            source.load(expected.artifactId()),
            "loaded artifact");
        if (!expected.artifactId().equals(loaded.key())) {
            throw new IllegalArgumentException(
                "artifact source returned a different key");
        }

        byte[] bytes = loaded.bytes();
        requireByteLength(bytes);
        String utf8 = decodeStrictUtf8(bytes);
        requireCompactObjectBoundary(bytes, utf8);
        ArtifactReference actual = ArtifactReference.describe(
            expected.role(),
            expected.contentSchema(),
            expected.mediaType(),
            bytes);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                "loaded exact plan artifact differs from its reference");
        }
        return new VerifiedBytes(expected, bytes, utf8);
    }

    private static void requireMetadata(
        ArtifactReference reference,
        String requiredRole,
        String requiredContentSchema,
        String requiredMediaType
    ) {
        Objects.requireNonNull(reference, "expected");
        if (!REFERENCE_SCHEMA.equals(reference.referenceSchema())
                || !requiredRole.equals(reference.role())
                || !requiredContentSchema.equals(reference.contentSchema())
                || !requiredMediaType.equals(reference.mediaType())) {
            throw new IllegalArgumentException(
                "artifact reference has the wrong exact plan role or schema");
        }
    }

    private static String decodeStrictUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "exact plan artifact is not valid UTF-8",
                exception);
        }
    }

    private static void requireCompactObjectBoundary(
        byte[] bytes,
        String text
    ) {
        if (startsWith(bytes, UTF8_BOM)) {
            throw new IllegalArgumentException(
                "exact plan artifact must not contain a UTF-8 BOM");
        }
        if (!text.equals(text.strip())
                || !text.startsWith("{")
                || !text.endsWith("}")) {
            throw new IllegalArgumentException(
                "exact plan artifact must be one compact JSON object");
        }
    }

    private static boolean startsWith(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (value[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static void requireByteLength(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 1 || bytes.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException(
                "exact plan artifact byte length is outside limits");
        }
    }

    private static String artifactId(
        String role,
        String contentSchema,
        String mediaType,
        String byteHash,
        int byteLength
    ) {
        return SchematicProofPlan.hash(lengthPrefixed(
            REFERENCE_SCHEMA,
            role,
            contentSchema,
            mediaType,
            byteHash,
            Integer.toString(byteLength)));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 unavailable",
                exception);
        }
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value.getBytes(StandardCharsets.UTF_8).length)
                .append(':')
                .append(value);
        }
        return result.toString();
    }

    public record ArtifactReference(
        String referenceSchema,
        String artifactId,
        String role,
        String contentSchema,
        String mediaType,
        String byteHash,
        int byteLength
    ) {
        public ArtifactReference {
            if (!REFERENCE_SCHEMA.equals(referenceSchema)) {
                throw new IllegalArgumentException(
                    "unsupported exact plan artifact reference schema");
            }
            artifactId = SchematicProofPlan.requireSha256(
                artifactId,
                "artifactId");
            role = SchematicProofPlan.requireToken(role, "artifact role");
            contentSchema = SchematicProofPlan.requireToken(
                contentSchema,
                "artifact content schema");
            if (mediaType == null || !MEDIA_TYPE.matcher(mediaType).matches()) {
                throw new IllegalArgumentException(
                    "artifact media type is not canonical");
            }
            byteHash = SchematicProofPlan.requireSha256(
                byteHash,
                "byteHash");
            if (byteLength < 1 || byteLength > MAX_ARTIFACT_BYTES) {
                throw new IllegalArgumentException(
                    "artifact byte length is outside limits");
            }
            String expectedId =
                ExactFinitePolynomialPlanReplayArtifactVerifier.artifactId(
                    role,
                    contentSchema,
                    mediaType,
                    byteHash,
                    byteLength);
            if (!expectedId.equals(artifactId)) {
                throw new IllegalArgumentException(
                    "artifactId does not match reference metadata");
            }
        }

        public static ArtifactReference describe(
            String role,
            String contentSchema,
            String mediaType,
            byte[] bytes
        ) {
            requireByteLength(bytes);
            byte[] snapshot = Arrays.copyOf(bytes, bytes.length);
            String byteHash = sha256(snapshot);
            return new ArtifactReference(
                REFERENCE_SCHEMA,
                ExactFinitePolynomialPlanReplayArtifactVerifier.artifactId(
                    role,
                    contentSchema,
                    mediaType,
                    byteHash,
                    snapshot.length),
                role,
                contentSchema,
                mediaType,
                byteHash,
                snapshot.length);
        }

        public String toCanonicalJson() {
            return new JsonWriter().beginObject()
                .property("referenceSchema", referenceSchema)
                .property("artifactId", artifactId)
                .property("role", role)
                .property("contentSchema", contentSchema)
                .property("mediaType", mediaType)
                .property("byteHash", byteHash)
                .property("byteLength", byteLength)
                .endObject()
                .toString();
        }
    }

    @FunctionalInterface
    public interface ArtifactSource {
        LoadedArtifact load(String artifactId);
    }

    public record LoadedArtifact(String key, byte[] bytes) {
        public LoadedArtifact {
            key = Objects.requireNonNull(key, "key");
            bytes = Arrays.copyOf(
                Objects.requireNonNull(bytes, "bytes"),
                bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }

    /**
     * Read-only result surface whose sole implementation is private to this
     * verifier. Receiving this value confirms only the byte boundary.
     */
    public sealed interface VerifiedArtifactBytes permits VerifiedBytes {
        ArtifactReference reference();

        int byteLength();

        String utf8();

        byte[] copyBytes();
    }

    private static final class VerifiedBytes
            implements VerifiedArtifactBytes {
        private final ArtifactReference reference;
        private final byte[] bytes;
        private final String utf8;

        private VerifiedBytes(
            ArtifactReference reference,
            byte[] bytes,
            String utf8
        ) {
            this.reference = Objects.requireNonNull(reference, "reference");
            this.bytes = Arrays.copyOf(bytes, bytes.length);
            this.utf8 = Objects.requireNonNull(utf8, "utf8");
        }

        @Override
        public ArtifactReference reference() {
            return reference;
        }

        @Override
        public int byteLength() {
            return bytes.length;
        }

        @Override
        public String utf8() {
            return utf8;
        }

        @Override
        public byte[] copyBytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }
    }
}
