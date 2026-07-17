package de.regelsuche.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.plugin.PluginTrustStore.ArtifactRevocation;
import de.regelsuche.plugin.PluginTrustStore.KeyStatus;
import de.regelsuche.plugin.PluginTrustStore.PublisherKey;
import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.ChainCheckpoint;
import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.Status;
import de.regelsuche.plugin.PluginTrustStoreRevisionVerifier.VerifiedTrustStoreRevision;
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

class PluginTrustStoreRevisionTest {
    private static final String PRIVATE_KEY_BASE64 =
        "MC4CAQAwBQYDK2VwBCIEIMiheLtWUyHyTH5K4ObomNdyw8wYX2pjwLsDNyYAO86v";
    private static final String PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEAH0nHmwIVDzgfBwZMgSccK3RZqKbROm8/OPg5KGpDcZk=";
    private static final String TRUST_DOMAIN = "regelsuche-community-trust";
    private static final String AUTHORITY_ID = "regelsuche-trust-authority";
    private static final String AUTHORITY_KEY_ID = "trust-root-2026";
    private static final String PUBLISHER_ID = "org.example.publisher";

    @Test
    void acceptsGenesisAndHashChainedSuccessorAndRetainsEvidence()
            throws Exception {
        PluginTrustStore root = rootTrustStore(KeyStatus.ACTIVE, AUTHORITY_KEY_ID);
        PluginTrustStoreRevisionVerifier verifier =
            new PluginTrustStoreRevisionVerifier(root);

        PluginTrustStore firstStore = firstTrustStore();
        PluginTrustStoreRevision firstRevision = sign(
            firstStore, 1L, "", TRUST_DOMAIN, AUTHORITY_ID, AUTHORITY_KEY_ID);
        VerifiedTrustStoreRevision first = verifier.requireTrusted(
            firstStore, firstRevision, null);

        assertEquals(Status.VERIFIED_TRUSTED, first.verification().status());
        assertTrue(first.verification().signatureVerified());
        assertTrue(first.verification().trusted());
        assertTrue(first.verification().replaySafe());
        assertEquals(firstRevision.contentHash(), first.checkpoint().revisionHash());
        assertEquals(1L, first.checkpoint().sequence());
        assertEquals(firstStore, first.trustStore());

        PluginTrustStore secondStore = secondTrustStore();
        PluginTrustStoreRevision secondRevision = sign(
            secondStore,
            2L,
            firstRevision.contentHash(),
            TRUST_DOMAIN,
            AUTHORITY_ID,
            AUTHORITY_KEY_ID);
        VerifiedTrustStoreRevision second = verifier.requireTrusted(
            secondStore, secondRevision, first.checkpoint());

        assertEquals(Status.VERIFIED_TRUSTED, second.verification().status());
        assertTrue(second.verification().replaySafe());
        assertEquals(firstRevision.contentHash(),
            second.revision().previousRevisionHash());
        assertEquals(first.checkpoint().contentHash(),
            second.verification().previousCheckpointHash());
        assertEquals(2L, second.checkpoint().sequence());
        assertEquals(secondRevision.contentHash(),
            second.checkpoint().revisionHash());
        assertTrue(secondStore.isArtifactRevoked("sha256:" + "a".repeat(64)));

        Path output = Path.of(
            "build", "reports", "plugin-trust-store-revision");
        Files.createDirectories(output);
        root.write(output.resolve("root-trust-store.json"));
        firstStore.write(output.resolve("trust-store-1.json"));
        firstRevision.write(output.resolve("revision-1.json"));
        Files.writeString(
            output.resolve("verification-1.json"),
            first.verification().toCanonicalJson(),
            StandardCharsets.UTF_8);
        first.checkpoint().write(output.resolve("checkpoint-1.json"));
        secondStore.write(output.resolve("trust-store-2.json"));
        secondRevision.write(output.resolve("revision-2.json"));
        Files.writeString(
            output.resolve("verification-2.json"),
            second.verification().toCanonicalJson(),
            StandardCharsets.UTF_8);
        second.checkpoint().write(output.resolve("checkpoint-2.json"));

        assertEquals(secondRevision, PluginTrustStoreRevision.read(
            output.resolve("revision-2.json")));
        assertEquals(second.checkpoint(), ChainCheckpoint.read(
            output.resolve("checkpoint-2.json")));
        assertEquals(second.verification().toCanonicalJson(), Files.readString(
            output.resolve("verification-2.json")));

        var constructors = VerifiedTrustStoreRevision.class.getDeclaredConstructors();
        assertEquals(1, constructors.length);
        assertTrue(Modifier.isPrivate(constructors[0].getModifiers()));
    }

    @Test
    void rejectsReplayGapsForksWrongDomainsAndMissingGenesis() throws Exception {
        PluginTrustStore root = rootTrustStore(KeyStatus.ACTIVE, AUTHORITY_KEY_ID);
        PluginTrustStoreRevisionVerifier verifier =
            new PluginTrustStoreRevisionVerifier(root);
        PluginTrustStore store = firstTrustStore();
        PluginTrustStoreRevision genesis = sign(
            store, 1L, "", TRUST_DOMAIN, AUTHORITY_ID, AUTHORITY_KEY_ID);
        ChainCheckpoint checkpoint = verifier.requireTrusted(
            store, genesis, null).checkpoint();

        assertEquals(Status.REPLAYED_REVISION,
            verifier.verify(store, genesis, checkpoint).status());

        PluginTrustStoreRevision gap = sign(
            store,
            3L,
            genesis.contentHash(),
            TRUST_DOMAIN,
            AUTHORITY_ID,
            AUTHORITY_KEY_ID);
        assertEquals(Status.SEQUENCE_GAP,
            verifier.verify(store, gap, checkpoint).status());

        PluginTrustStoreRevision fork = sign(
            store,
            2L,
            "sha256:" + "b".repeat(64),
            TRUST_DOMAIN,
            AUTHORITY_ID,
            AUTHORITY_KEY_ID);
        assertEquals(Status.PREVIOUS_HASH_MISMATCH,
            verifier.verify(store, fork, checkpoint).status());

        PluginTrustStoreRevision otherDomain = sign(
            store,
            2L,
            genesis.contentHash(),
            "other-community-trust",
            AUTHORITY_ID,
            AUTHORITY_KEY_ID);
        assertEquals(Status.TRUST_DOMAIN_MISMATCH,
            verifier.verify(store, otherDomain, checkpoint).status());

        PluginTrustStoreRevision successor = sign(
            store,
            2L,
            genesis.contentHash(),
            TRUST_DOMAIN,
            AUTHORITY_ID,
            AUTHORITY_KEY_ID);
        assertEquals(Status.GENESIS_REQUIRED,
            verifier.verify(store, successor, null).status());
        SecurityException failure = assertThrows(SecurityException.class, () ->
            verifier.requireTrusted(store, successor, null));
        assertTrue(failure.getMessage().contains("status=GENESIS_REQUIRED"));
        assertTrue(failure.getMessage().contains("sequence=2"));
    }

    @Test
    void rejectsWrongStoreInvalidSignatureAndUntrustedAuthorityKeys()
            throws Exception {
        PluginTrustStore store = firstTrustStore();
        PluginTrustStoreRevision valid = sign(
            store, 1L, "", TRUST_DOMAIN, AUTHORITY_ID, AUTHORITY_KEY_ID);

        PluginTrustStoreRevisionVerifier active =
            new PluginTrustStoreRevisionVerifier(
                rootTrustStore(KeyStatus.ACTIVE, AUTHORITY_KEY_ID));
        assertEquals(Status.TRUST_STORE_HASH_MISMATCH,
            active.verify(secondTrustStore(), valid, null).status());

        byte[] invalidBytes = Base64.getDecoder().decode(valid.signatureBase64());
        invalidBytes[0] ^= 0x01;
        PluginTrustStoreRevision invalid = PluginTrustStoreRevision.create(
            valid.trustDomainId(),
            valid.sequence(),
            valid.previousRevisionHash(),
            valid.trustStoreContentHash(),
            valid.authorityId(),
            valid.keyId(),
            Base64.getEncoder().encodeToString(invalidBytes));
        assertEquals(Status.INVALID_SIGNATURE,
            active.verify(store, invalid, null).status());

        PluginTrustStoreRevisionVerifier unknownAuthority =
            new PluginTrustStoreRevisionVerifier(PluginTrustStore.empty());
        assertEquals(Status.UNKNOWN_AUTHORITY,
            unknownAuthority.verify(store, valid, null).status());

        PluginTrustStoreRevisionVerifier unknownKey =
            new PluginTrustStoreRevisionVerifier(
                rootTrustStore(KeyStatus.ACTIVE, "different-root-key"));
        assertEquals(Status.UNKNOWN_KEY,
            unknownKey.verify(store, valid, null).status());

        PluginTrustStoreRevisionVerifier revoked =
            new PluginTrustStoreRevisionVerifier(
                rootTrustStore(KeyStatus.REVOKED, AUTHORITY_KEY_ID));
        assertEquals(Status.REVOKED_KEY,
            revoked.verify(store, valid, null).status());

        PluginTrustStoreRevisionVerifier retired =
            new PluginTrustStoreRevisionVerifier(
                rootTrustStore(KeyStatus.RETIRED, AUTHORITY_KEY_ID));
        var retiredVerification = retired.verify(store, valid, null);
        assertEquals(Status.VERIFIED_RETIRED_KEY,
            retiredVerification.status());
        assertTrue(retiredVerification.trusted());
        assertTrue(retiredVerification.replaySafe());
        assertEquals(List.of("RETIRED_TRUST_AUTHORITY_KEY"),
            retiredVerification.warnings());
    }

    @Test
    void revisionCheckpointAndVerificationContractsAreStrict(
        @TempDir Path tempDir
    ) throws Exception {
        PluginTrustStore store = firstTrustStore();
        PluginTrustStoreRevision revision = sign(
            store, 1L, "", TRUST_DOMAIN, AUTHORITY_ID, AUTHORITY_KEY_ID);
        PluginTrustStoreRevisionVerifier verifier =
            new PluginTrustStoreRevisionVerifier(
                rootTrustStore(KeyStatus.ACTIVE, AUTHORITY_KEY_ID));
        VerifiedTrustStoreRevision verified = verifier.requireTrusted(
            store, revision, null);

        assertThrows(IllegalArgumentException.class, () ->
            PluginTrustStoreRevision.create(
                TRUST_DOMAIN,
                1L,
                "sha256:" + "1".repeat(64),
                revision.trustStoreContentHash(),
                AUTHORITY_ID,
                AUTHORITY_KEY_ID,
                revision.signatureBase64()));
        assertThrows(IllegalArgumentException.class, () ->
            PluginTrustStoreRevision.create(
                TRUST_DOMAIN,
                2L,
                "",
                revision.trustStoreContentHash(),
                AUTHORITY_ID,
                AUTHORITY_KEY_ID,
                revision.signatureBase64()));
        assertThrows(IllegalArgumentException.class, () ->
            PluginTrustStoreRevision.create(
                TRUST_DOMAIN,
                1L,
                "",
                revision.trustStoreContentHash(),
                AUTHORITY_ID,
                AUTHORITY_KEY_ID,
                nonCanonicalBase64(revision.signatureBase64())));

        Path unknownRevision = tempDir.resolve("unknown-revision.json");
        Files.writeString(
            unknownRevision,
            revision.toCanonicalJson().replaceFirst(
                "\\{", "{\"unknown\":true,"),
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
            PluginTrustStoreRevision.read(unknownRevision));

        Path trailingRevision = tempDir.resolve("trailing-revision.json");
        Files.writeString(
            trailingRevision,
            revision.toCanonicalJson() + "{}",
            StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () ->
            PluginTrustStoreRevision.read(trailingRevision));

        ChainCheckpoint checkpoint = verified.checkpoint();
        assertThrows(IllegalArgumentException.class, () ->
            new ChainCheckpoint(
                checkpoint.schema(),
                checkpoint.trustDomainId(),
                checkpoint.sequence(),
                checkpoint.revisionHash(),
                "sha256:" + "0".repeat(64)));

        var verification = verified.verification();
        assertThrows(IllegalArgumentException.class, () ->
            new PluginTrustStoreRevisionVerifier.Verification(
                verification.schema(),
                verification.trustDomainId(),
                verification.sequence(),
                verification.revisionHash(),
                verification.previousRevisionHash(),
                verification.trustStoreContentHash(),
                verification.rootTrustStoreContentHash(),
                verification.previousCheckpointHash(),
                verification.status(),
                verification.signatureVerified(),
                verification.trusted(),
                verification.replaySafe(),
                verification.authorityId(),
                verification.keyId(),
                verification.warnings(),
                "sha256:" + "0".repeat(64)));

        assertThrows(NullPointerException.class, () ->
            verifier.verify(null, revision, null));
        assertThrows(NullPointerException.class, () ->
            verifier.verify(store, null, null));
    }

    private static PluginTrustStore rootTrustStore(
        KeyStatus status,
        String keyId
    ) {
        return new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(new PublisherKey(
                AUTHORITY_ID,
                keyId,
                PluginTrustStoreRevision.ALGORITHM,
                PUBLIC_KEY_BASE64,
                status,
                "")),
            List.of());
    }

    private static PluginTrustStore firstTrustStore() {
        return new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(new PublisherKey(
                PUBLISHER_ID,
                "release-2026",
                PluginSignatureManifest.ALGORITHM,
                PUBLIC_KEY_BASE64,
                KeyStatus.ACTIVE,
                "")),
            List.of());
    }

    private static PluginTrustStore secondTrustStore() {
        return new PluginTrustStore(
            PluginTrustStore.SCHEMA,
            List.of(
                new PublisherKey(
                    PUBLISHER_ID,
                    "release-2026",
                    PluginSignatureManifest.ALGORITHM,
                    PUBLIC_KEY_BASE64,
                    KeyStatus.RETIRED,
                    "release-2027"),
                new PublisherKey(
                    PUBLISHER_ID,
                    "release-2027",
                    PluginSignatureManifest.ALGORITHM,
                    PUBLIC_KEY_BASE64,
                    KeyStatus.ACTIVE,
                    "")),
            List.of(new ArtifactRevocation(
                "sha256:" + "a".repeat(64),
                "publisher incident response")));
    }

    private static PluginTrustStoreRevision sign(
        PluginTrustStore store,
        long sequence,
        String previousRevisionHash,
        String trustDomainId,
        String authorityId,
        String keyId
    ) throws Exception {
        String storeHash = PluginArtifactVerifier.sha256(
            store.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
        byte[] payload = PluginTrustStoreRevision.signedPayload(
            trustDomainId,
            sequence,
            previousRevisionHash,
            storeHash,
            authorityId,
            keyId,
            PluginTrustStoreRevision.ALGORITHM);
        Signature signer = Signature.getInstance(
            PluginTrustStoreRevision.ALGORITHM);
        signer.initSign(fixedKeyPair().getPrivate());
        signer.update(payload);
        return PluginTrustStoreRevision.create(
            trustDomainId,
            sequence,
            previousRevisionHash,
            storeHash,
            authorityId,
            keyId,
            Base64.getEncoder().encodeToString(signer.sign()));
    }

    private static KeyPair fixedKeyPair() throws Exception {
        KeyFactory factory = KeyFactory.getInstance(
            PluginTrustStoreRevision.ALGORITHM);
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
            Base64.getDecoder().decode(PRIVATE_KEY_BASE64)));
        return new KeyPair(
            factory.generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(PUBLIC_KEY_BASE64))),
            privateKey);
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
}
