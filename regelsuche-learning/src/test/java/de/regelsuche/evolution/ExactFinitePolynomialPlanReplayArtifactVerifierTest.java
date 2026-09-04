package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialPlanReplayArtifactVerifierTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayVerifier replayVerifier =
        new ExactFinitePolynomialPlanReplayVerifier();
    private final ExactFinitePolynomialPlanReplayArtifactVerifier artifactVerifier =
        new ExactFinitePolynomialPlanReplayArtifactVerifier();

    @Test
    void verifiesLoadedReceiptBytesAndRetainsAnImmutableSnapshot() {
        ReplayReceipt receipt = receipt();
        byte[] sourceBytes = receipt.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
        ArtifactReference reference = artifactVerifier.describeReceipt(receipt);
        LoadedArtifact loaded = new LoadedArtifact(
            reference.artifactId(),
            sourceBytes);

        sourceBytes[0] = '[';
        VerifiedArtifactBytes verified = artifactVerifier.verifyReceipt(
            reference,
            requested -> {
                assertEquals(reference.artifactId(), requested);
                return loaded;
            });

        assertEquals(reference, verified.reference());
        assertEquals(reference.byteLength(), verified.byteLength());
        assertEquals(receipt.toCanonicalJson(), verified.utf8());
        assertArrayEquals(
            receipt.toCanonicalJson().getBytes(StandardCharsets.UTF_8),
            verified.copyBytes());

        byte[] callerCopy = verified.copyBytes();
        callerCopy[0] = '[';
        assertEquals('{', verified.copyBytes()[0]);
        assertEquals(
            reference.artifactId(),
            ArtifactReference.describe(
                reference.role(),
                reference.contentSchema(),
                reference.mediaType(),
                verified.copyBytes()).artifactId());
    }

    @Test
    void rejectsWrongStoreKeysAndChangedPayloads() {
        ReplayReceipt receipt = receipt();
        ArtifactReference reference = artifactVerifier.describeReceipt(receipt);
        byte[] bytes = receipt.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () ->
            artifactVerifier.verifyReceipt(
                reference,
                ignored -> new LoadedArtifact(hash("wrong-key"), bytes)));

        byte[] changed = Arrays.copyOf(bytes, bytes.length);
        changed[changed.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () ->
            artifactVerifier.verifyReceipt(
                reference,
                ignored -> new LoadedArtifact(
                    reference.artifactId(),
                    changed)));

        byte[] changedBeforeLoad = Arrays.copyOf(bytes, bytes.length);
        changedBeforeLoad[1] ^= 1;
        assertThrows(IllegalArgumentException.class, () ->
            artifactVerifier.verifyReceipt(
                reference,
                ignored -> new LoadedArtifact(
                    reference.artifactId(),
                    changedBeforeLoad)));
    }

    @Test
    void rejectsReferenceMetadataAndArtifactIdSubstitution() {
        ArtifactReference reference = artifactVerifier.describeReceipt(receipt());

        assertThrows(IllegalArgumentException.class, () ->
            new ArtifactReference(
                reference.referenceSchema(),
                reference.artifactId(),
                reference.role(),
                reference.contentSchema(),
                reference.mediaType(),
                reference.byteHash(),
                reference.byteLength() + 1));
        assertThrows(IllegalArgumentException.class, () ->
            new ArtifactReference(
                reference.referenceSchema(),
                reference.artifactId(),
                reference.role(),
                reference.contentSchema(),
                reference.mediaType(),
                hash("different-bytes"),
                reference.byteLength()));
        assertThrows(IllegalArgumentException.class, () ->
            new ArtifactReference(
                reference.referenceSchema(),
                hash("different-artifact"),
                reference.role(),
                reference.contentSchema(),
                reference.mediaType(),
                reference.byteHash(),
                reference.byteLength()));
    }

    @Test
    void rejectsMalformedEncodingBomAndOuterNonCanonicalBytes() {
        assertRejectedBytes(new byte[] {'{', (byte) 0xc3, '}'});
        assertRejectedBytes(new byte[] {
            (byte) 0xef,
            (byte) 0xbb,
            (byte) 0xbf,
            '{', '}'
        });
        assertRejectedBytes(" {}".getBytes(StandardCharsets.UTF_8));
        assertRejectedBytes("{}\n".getBytes(StandardCharsets.UTF_8));
        assertRejectedBytes("[]".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void separatesRoleAndSchemaInTheArtifactAddress() {
        byte[] bytes = receipt().toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
        ArtifactReference receipt = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE,
            ReplayReceipt.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_MEDIA_TYPE,
            bytes);
        ArtifactReference otherRole = ArtifactReference.describe(
            "plan-run",
            ReplayReceipt.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_MEDIA_TYPE,
            bytes);
        ArtifactReference otherSchema = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE,
            "regelsuche.exact-finite-polynomial-plan-replay-receipt/v2",
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_MEDIA_TYPE,
            bytes);

        assertNotEquals(receipt.artifactId(), otherRole.artifactId());
        assertNotEquals(receipt.artifactId(), otherSchema.artifactId());
        assertThrows(IllegalArgumentException.class, () ->
            artifactVerifier.verifyReceipt(
                otherRole,
                ignored -> new LoadedArtifact(
                    otherRole.artifactId(),
                    bytes)));
        assertThrows(IllegalArgumentException.class, () ->
            artifactVerifier.verifyReceipt(
                otherSchema,
                ignored -> new LoadedArtifact(
                    otherSchema.artifactId(),
                    bytes)));
    }

    @Test
    void verifiesOnlyBytesRatherThanClaimingSemanticReceiptParsing() {
        byte[] compactObject = "{}".getBytes(StandardCharsets.UTF_8);
        ArtifactReference reference = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE,
            ReplayReceipt.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_MEDIA_TYPE,
            compactObject);

        VerifiedArtifactBytes verified = artifactVerifier.verifyReceipt(
            reference,
            ignored -> new LoadedArtifact(
                reference.artifactId(),
                compactObject));

        assertEquals("{}", verified.utf8());
        assertFalse(verified.utf8().contains("replayStatus"));
    }

    @Test
    void verifiedByteImplementationIsSealedPrivateAndVerifierOwned() {
        assertTrue(VerifiedArtifactBytes.class.isSealed());
        Class<?>[] permitted =
            VerifiedArtifactBytes.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertTrue(Modifier.isPrivate(permitted[0].getModifiers()));
        assertTrue(Arrays.stream(permitted[0].getDeclaredConstructors())
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())));
    }

    private void assertRejectedBytes(byte[] bytes) {
        ArtifactReference reference = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE,
            ReplayReceipt.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_MEDIA_TYPE,
            bytes);
        assertThrows(IllegalArgumentException.class, () ->
            artifactVerifier.verifyReceipt(
                reference,
                ignored -> new LoadedArtifact(
                    reference.artifactId(),
                    bytes)));
    }

    private ReplayReceipt receipt() {
        String source = "x^2 + 1";
        String ansatz = "x^2 + ${constant}";
        List<HoleDomain> domains = List.of(
            HoleDomain.integerRange("constant", 0, 2));
        SchematicProofPlan plan = resolver.createPlan(
            "artifact-byte-replay-plan",
            source,
            ansatz,
            domains,
            4,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            4);
        return replayVerifier.verify(
            plan,
            source,
            ansatz,
            domains,
            4,
            run);
    }

    private static String hash(String value) {
        return SchematicProofPlan.hash(value);
    }
}
