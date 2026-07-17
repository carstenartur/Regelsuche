package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginArtifactIndexVerifier.Status;
import de.regelsuche.plugin.PluginTrustStore.KeyStatus;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginArtifactIndexSignatureTest {
    private static final String PRIVATE_KEY_BASE64 =
        "MC4CAQAwBQYDK2VwBCIEIMiheLtWUyHyTH5K4ObomNdyw8wYX2pjwLsDNyYAO86v";
    private static final String PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEAH0nHmwIVDzgfBwZMgSccK3RZqKbROm8/OPg5KGpDcZk=";
    private static final String CURATOR_ID = "regelsuche-curators";
    private static final String KEY_ID = "index-release-2026";

    @Test
    void verifiesAndLoadsSignedImmutableIndex(@TempDir Path tempDir)
            throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        PluginArtifactIndexSignature signature = sign(index);
        PluginTrustStore trustStore = trustStore(KeyStatus.ACTIVE);
        PluginArtifactIndexVerifier verifier =
            new PluginArtifactIndexVerifier(trustStore);

        PluginArtifactIndexVerifier.Verification verification =
            verifier.verify(index, signature);

        assertEquals(Status.VERIFIED_TRUSTED, verification.status());
        assertTrue(verification.signatureVerified());
        assertTrue(verification.trusted());
        assertEquals(index.contentHash(), verification.indexContentHash());
        assertEquals(CURATOR_ID, verification.curatorId());
        assertEquals(KEY_ID, verification.keyId());
        assertTrue(verification.warnings().isEmpty());

        Path indexPath = index.write(tempDir.resolve("index.json"));
        Path signaturePath = signature.write(
            PluginArtifactIndexSignature.sidecarFor(indexPath));
        PluginArtifactIndexVerifier.VerifiedIndex verified =
            verifier.requireTrusted(indexPath, signaturePath);
        assertEquals(index, verified.index());
        assertEquals(verification, verified.verification());

        var constructors =
            PluginArtifactIndexVerifier.VerifiedIndex.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));

        Path evidence = Path.of("build", "reports", "plugin-artifact-index");
        Files.createDirectories(evidence);
        Files.writeString(
            evidence.resolve("index-signature.json"),
            signature.toCanonicalJson(),
            StandardCharsets.UTF_8);
        Files.writeString(
            evidence.resolve("index-verification.json"),
            verification.toCanonicalJson(),
            StandardCharsets.UTF_8);
        Files.writeString(
            evidence.resolve("index-trust-store.json"),
            trustStore.toCanonicalJson(),
            StandardCharsets.UTF_8);
        assertEquals(signature.toCanonicalJson(), Files.readString(
            evidence.resolve("index-signature.json")));
        assertEquals(verification.toCanonicalJson(), Files.readString(
            evidence.resolve("index-verification.json")));
    }

    @Test
    void failsClosedForInvalidSignatureAndMismatchedRevision(
        @TempDir Path tempDir
    ) throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        PluginArtifactIndexSignature valid = sign(index);
        byte[] invalidBytes = Base64.getDecoder().decode(valid.signatureBase64());
        invalidBytes[0] ^= 0x01;
        PluginArtifactIndexSignature invalid = PluginArtifactIndexSignature.create(
            valid.indexId(),
            valid.revision(),
            valid.indexContentHash(),
            valid.curatorId(),
            valid.keyId(),
            Base64.getEncoder().encodeToString(invalidBytes));
        PluginArtifactIndexVerifier verifier =
            new PluginArtifactIndexVerifier(trustStore(KeyStatus.ACTIVE));

        var invalidVerification = verifier.verify(index, invalid);
        assertEquals(Status.INVALID_SIGNATURE, invalidVerification.status());
        assertFalse(invalidVerification.signatureVerified());
        assertFalse(invalidVerification.trusted());

        PluginArtifactIndexSignature wrongRevision = sign(
            index.indexId(),
            "2026-07-17.2",
            index.contentHash(),
            index.curatorId());
        assertEquals(Status.REVISION_MISMATCH,
            verifier.verify(index, wrongRevision).status());

        Path indexPath = index.write(tempDir.resolve("index.json"));
        invalid.write(PluginArtifactIndexSignature.sidecarFor(indexPath));
        assertThrows(SecurityException.class, () ->
            verifier.requireTrusted(indexPath));
    }

    @Test
    void distinguishesRetiredRevokedAndUnknownCuratorKeys() throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        PluginArtifactIndexSignature signature = sign(index);

        var retired = new PluginArtifactIndexVerifier(
            trustStore(KeyStatus.RETIRED)).verify(index, signature);
        assertEquals(Status.VERIFIED_RETIRED_KEY, retired.status());
        assertTrue(retired.trusted());
        assertEquals(List.of("RETIRED_CURATOR_KEY"), retired.warnings());

        var revoked = new PluginArtifactIndexVerifier(
            trustStore(KeyStatus.REVOKED)).verify(index, signature);
        assertEquals(Status.REVOKED_KEY, revoked.status());
        assertFalse(revoked.trusted());

        var unknown = new PluginArtifactIndexVerifier(
            PluginTrustStore.empty()).verify(index, signature);
        assertEquals(Status.UNKNOWN_CURATOR, unknown.status());
        assertFalse(unknown.trusted());
    }

    @Test
    void signatureParsingAndVerificationEvidenceAreStrict(
        @TempDir Path tempDir
    ) throws Exception {
        PluginArtifactIndex index = PluginArtifactIndexFixtures.referenceIndex();
        PluginArtifactIndexSignature signature = sign(index);
        Path unknown = tempDir.resolve("unknown-signature.json");
        Files.writeString(
            unknown,
            signature.toCanonicalJson().replaceFirst(
                "\\{", "{\"unknown\":true,"),
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndexSignature.read(unknown));

        String nonCanonical = nonCanonicalBase64(signature.signatureBase64());
        assertThrows(IllegalArgumentException.class, () ->
            PluginArtifactIndexSignature.create(
                signature.indexId(),
                signature.revision(),
                signature.indexContentHash(),
                signature.curatorId(),
                signature.keyId(),
                nonCanonical));

        var verification = new PluginArtifactIndexVerifier(
            trustStore(KeyStatus.ACTIVE)).verify(index, signature);
        assertThrows(IllegalArgumentException.class, () ->
            new PluginArtifactIndexVerifier.Verification(
                verification.schema(),
                verification.indexId(),
                verification.revision(),
                verification.indexContentHash(),
                verification.signatureContentHash(),
                verification.trustStoreContentHash(),
                verification.status(),
                verification.signatureVerified(),
                verification.trusted(),
                verification.curatorId(),
                verification.keyId(),
                verification.warnings(),
                "sha256:" + "0".repeat(64)));
    }

    private static PluginArtifactIndexSignature sign(PluginArtifactIndex index)
            throws Exception {
        return sign(
            index.indexId(),
            index.revision(),
            index.contentHash(),
            index.curatorId());
    }

    private static PluginArtifactIndexSignature sign(
        String indexId,
        String revision,
        String indexContentHash,
        String curatorId
    ) throws Exception {
        byte[] payload = PluginArtifactIndexSignature.signedPayload(
            indexId,
            revision,
            indexContentHash,
            curatorId,
            KEY_ID,
            PluginArtifactIndexSignature.ALGORITHM);
        Signature signer = Signature.getInstance(
            PluginArtifactIndexSignature.ALGORITHM);
        signer.initSign(fixedKeyPair().getPrivate());
        signer.update(payload);
        return PluginArtifactIndexSignature.create(
            indexId,
            revision,
            indexContentHash,
            curatorId,
            KEY_ID,
            Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static String nonCanonicalBase64(String canonical) {
        String alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int position = canonical.length() - 3;
        int current = alphabet.indexOf(canonical.charAt(position));
        int replacement = (current & 0b110000) | 0b000001;
        if (replacement == current) {
            replacement = (current & 0b110000) | 0b000010;
        }
        return canonical.substring(0, position)
            + alphabet.charAt(replacement)
            + canonical.substring(position + 1);
    }

    private static PluginTrustStore trustStore(KeyStatus status) {
        return new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(new PublisherKey(
                CURATOR_ID,
                KEY_ID,
                PluginArtifactIndexSignature.ALGORITHM,
                PUBLIC_KEY_BASE64,
                status,
                "")),
            List.of());
    }

    private static KeyPair fixedKeyPair() throws Exception {
        KeyFactory factory = KeyFactory.getInstance(
            PluginArtifactIndexSignature.ALGORITHM);
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
            Base64.getDecoder().decode(PRIVATE_KEY_BASE64)));
        return new KeyPair(
            factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(PUBLIC_KEY_BASE64))),
            privateKey);
    }
}
