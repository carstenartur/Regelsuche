package de.regelsuche.benchmark.polynomial;

import de.regelsuche.util.AtomicJsonFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical target-blind freeze of a complete measured candidate batch. */
public record PolynomialTheoryUtilityCandidateFreeze(
    String contentHash,
    long byteLength,
    PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch,
    String canonicalJson
) {
    public static final String SCHEMA =
        "regelsuche.polynomial-theory-utility-candidate-freeze/v1";
    public static final String FILE_NAME =
        "polynomial-theory-utility-candidate-freeze-v1.json";
    public static final String EVIDENCE_STATUS =
        "CANDIDATES_FROZEN_QUALIFICATION_NOT_OPENED";
    public static final String QUALIFICATION_EXPOSURE =
        "HASH_ONLY_NOT_OPENED";

    private static final Pattern SHA_256 =
        Pattern.compile("sha256:[0-9a-f]{64}");

    public PolynomialTheoryUtilityCandidateFreeze {
        contentHash = requireHash(contentHash, "contentHash");
        if (byteLength < 1L) {
            throw new IllegalArgumentException(
                "candidate freeze byteLength must be positive"
            );
        }
        measuredBatch = Objects.requireNonNull(
            measuredBatch,
            "measuredBatch"
        );
        canonicalJson = requireText(canonicalJson, "canonicalJson");

        String expected =
            PolynomialTheoryUtilityCandidateFreezeJson.canonical(
                measuredBatch
            );
        if (!canonicalJson.equals(expected)) {
            throw new IllegalArgumentException(
                "candidate freeze differs from canonical measured bytes"
            );
        }
        byte[] bytes = utf8(canonicalJson);
        if (byteLength != bytes.length
                || !contentHash.equals(
                    PolynomialTheoryUtilityExecutionIdentity.sha256(bytes)
                )) {
            throw new IllegalArgumentException(
                "candidate freeze identity differs from canonical bytes"
            );
        }
    }

    public static PolynomialTheoryUtilityCandidateFreeze create(
        PolynomialTheoryUtilityCandidateMeasurementBatch measuredBatch
    ) {
        var retained = Objects.requireNonNull(
            measuredBatch,
            "measuredBatch"
        );
        String canonical =
            PolynomialTheoryUtilityCandidateFreezeJson.canonical(retained);
        byte[] bytes = utf8(canonical);
        return new PolynomialTheoryUtilityCandidateFreeze(
            PolynomialTheoryUtilityExecutionIdentity.sha256(bytes),
            bytes.length,
            retained,
            canonical
        );
    }

    public String schema() {
        return SCHEMA;
    }

    public String studyId() {
        return PolynomialTheoryUtilityPreregistration.STUDY_ID;
    }

    public String evidenceStatus() {
        return EVIDENCE_STATUS;
    }

    public String qualificationExposure() {
        return QUALIFICATION_EXPOSURE;
    }

    public int rowCount() {
        return measuredBatch.rowCount();
    }

    public byte[] bytes() {
        return utf8(canonicalJson);
    }

    public boolean verify(byte[] candidate) {
        return candidate != null
            && MessageDigest.isEqual(bytes(), candidate);
    }

    public void requireVerified(byte[] candidate) {
        if (!verify(candidate)) {
            throw new IllegalArgumentException(
                "candidate freeze bytes differ from the frozen artifact"
            );
        }
    }

    public void validateAgainst(
        PolynomialTheoryUtilityCandidateMeasurementBatch expected
    ) {
        if (!measuredBatch.equals(
                Objects.requireNonNull(expected, "expected"))) {
            throw new IllegalArgumentException(
                "candidate freeze refers to another measured batch"
            );
        }
    }

    public Path write(Path directory) throws IOException {
        Path root = Objects.requireNonNull(directory, "directory")
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(root);
        Path qualification = root.resolve(
            PolynomialTheoryUtilityCaseCorpus.QUALIFICATION_FILE_NAME
        );
        if (Files.exists(qualification, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                "candidate freeze output contains sealed qualification"
            );
        }
        Path target = root.resolve(FILE_NAME);
        AtomicJsonFile.writeUtf8(target, canonicalJson);
        requireVerified(Files.readAllBytes(target));
        if (Files.exists(qualification, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                "sealed qualification appeared during candidate freeze"
            );
        }
        return target;
    }

    private static byte[] utf8(String value) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                "candidate freeze contains invalid Unicode",
                exception
            );
        }
    }

    private static String requireHash(String value, String name) {
        String text = requireText(value, name);
        if (!SHA_256.matcher(text).matches()) {
            throw new IllegalArgumentException(name + " is not SHA-256");
        }
        return text;
    }

    private static String requireText(String value, String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
