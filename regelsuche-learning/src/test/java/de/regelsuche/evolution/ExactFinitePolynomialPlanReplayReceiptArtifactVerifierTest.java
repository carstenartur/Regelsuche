package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.VerifiedReplayReceiptArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialPlanReplayReceiptArtifactVerifierTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayVerifier replayVerifier =
        new ExactFinitePolynomialPlanReplayVerifier();
    private final ExactFinitePolynomialPlanReplayArtifactVerifier byteVerifier =
        new ExactFinitePolynomialPlanReplayArtifactVerifier();
    private final ExactFinitePolynomialPlanReplayReceiptArtifactVerifier
        semanticVerifier =
            new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier();

    @Test
    void verifiesCanonicalReceiptSemanticsAfterTheIndependentByteBoundary() {
        Fixture fixture = fixture(
            "semantic-receipt-plan",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);

        VerifiedArtifactBytes bytes = verified(fixture.receipt());
        VerifiedReplayReceiptArtifact verified = semanticVerifier.verify(bytes);

        assertEquals(bytes.reference(), verified.reference());
        assertEquals(fixture.plan().contentHash(), verified.planHash());
        assertEquals(fixture.run().contentHash(), verified.planRunHash());
        assertEquals(
            fixture.run().solverResult().contentHash(),
            verified.solverResultHash());
        assertEquals(
            fixture.receipt().solverRevisionHash(),
            verified.solverRevisionHash());
        assertEquals(fixture.receipt().runStatus(), verified.runStatus());
        assertEquals(
            fixture.receipt().resolvedCandidateHashes(),
            verified.resolvedCandidateHashes());
        assertEquals(
            fixture.receipt().contentHash(),
            verified.receiptContentHash());
        assertEquals(
            fixture.receipt().toCanonicalJson(),
            verified.canonicalJson());
        assertTrue(verified.matches(fixture.receipt()));
        assertFalse(verified.matches(null));
        assertTrue(verified.verificationHash().startsWith("sha256:"));
        assertEquals(
            verified.verificationHash(),
            semanticVerifier.verify(bytes).verificationHash());
    }

    @Test
    void preservesCompleteNullAndTruncatedReceiptSemantics() {
        Fixture none = fixture(
            "semantic-null-plan",
            "x^2 + 1",
            "(x + ${shift})^2",
            List.of(HoleDomain.integerRange("shift", -2, 2)),
            4);
        VerifiedReplayReceiptArtifact verifiedNone = semanticVerifier.verify(
            verified(none.receipt()));
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION,
            verifiedNone.runStatus());
        assertEquals(0, verifiedNone.matchingAssignments());
        assertEquals(0, verifiedNone.retainedSolutions());
        assertTrue(verifiedNone.resolvedCandidateHashes().isEmpty());

        Fixture truncated = fixture(
            "semantic-truncated-plan",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            1);
        VerifiedReplayReceiptArtifact verifiedTruncated =
            semanticVerifier.verify(verified(truncated.receipt()));
        assertEquals(
            ExactFinitePolynomialPlanRun.Status
                .COMPLETE_RESOLUTION_SET_TRUNCATED,
            verifiedTruncated.runStatus());
        assertEquals(2, verifiedTruncated.matchingAssignments());
        assertEquals(1, verifiedTruncated.retainedSolutions());
        assertEquals(1, verifiedTruncated.resolvedCandidateHashes().size());
    }

    @Test
    void rejectsByteValidButNonCanonicalOrInconsistentReceiptObjects() {
        assertThrows(
            IllegalArgumentException.class,
            () -> semanticVerifier.verify(verifiedJson("{}")));

        Fixture fixture = fixture(
            "semantic-rejection-plan",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        ReplayReceipt receipt = fixture.receipt();
        String json = receipt.toCanonicalJson();

        assertRejected(json.replace(
            "{\"schema\":",
            "{\"\\u0073chema\":"));
        assertRejected(json.replace(
            "\"verifierRevisionHash\":\""
                + receipt.verifierRevisionHash() + "\"",
            "\"verifierRevisionHash\":\""
                + hash("wrong-verifier-revision") + "\""));
        assertRejected(json.replace(
            "\"runStatus\":\"COMPLETE_WITH_RESOLUTIONS\"",
            "\"runStatus\":\"COMPLETE_WITHOUT_SOLUTION\""));
        assertRejected(json.replace(
            "\"totalAssignments\":" + receipt.totalAssignments(),
            "\"totalAssignments\":0" + receipt.totalAssignments()));
        assertRejected(json.replace(
            ",\"contentHash\":",
            ",\"extra\":\"not-admitted\",\"contentHash\":"));
        assertRejected(json.replace(
            receipt.contentHash(),
            hash("wrong-receipt-content")));
    }

    @Test
    void rejectsUnsortedAndDuplicateResolvedCandidateIdentities() {
        Fixture fixture = fixture(
            "semantic-two-candidate-plan",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            2);
        List<String> hashes = fixture.receipt().resolvedCandidateHashes();
        assertEquals(2, hashes.size());
        String ordered = "\"" + hashes.get(0) + "\",\""
            + hashes.get(1) + "\"";
        String reversed = "\"" + hashes.get(1) + "\",\""
            + hashes.get(0) + "\"";
        String duplicate = "\"" + hashes.get(0) + "\",\""
            + hashes.get(0) + "\"";
        String json = fixture.receipt().toCanonicalJson();

        assertNotEquals(ordered, reversed);
        assertRejected(json.replace(ordered, reversed));
        assertRejected(json.replace(ordered, duplicate));
    }

    @Test
    void semanticResultImplementationIsSealedPrivateAndVerifierOwned() {
        assertTrue(VerifiedReplayReceiptArtifact.class.isSealed());
        Class<?>[] permitted =
            VerifiedReplayReceiptArtifact.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertTrue(Modifier.isPrivate(permitted[0].getModifiers()));
        assertTrue(Arrays.stream(permitted[0].getDeclaredConstructors())
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())));
    }

    private void assertRejected(String json) {
        assertThrows(
            IllegalArgumentException.class,
            () -> semanticVerifier.verify(verifiedJson(json)));
    }

    private VerifiedArtifactBytes verified(ReplayReceipt receipt) {
        ArtifactReference reference = byteVerifier.describeReceipt(receipt);
        byte[] bytes = receipt.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
        return byteVerifier.verifyReceipt(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));
    }

    private VerifiedArtifactBytes verifiedJson(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ArtifactReference reference = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE,
            ReplayReceipt.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_MEDIA_TYPE,
            bytes);
        return byteVerifier.verifyReceipt(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));
    }

    private Fixture fixture(
        String planId,
        String source,
        String ansatz,
        List<HoleDomain> domains,
        int retainedSolutionLimit
    ) {
        SchematicProofPlan plan = resolver.createPlan(
            planId,
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit);
        ReplayReceipt receipt = replayVerifier.verify(
            plan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            run);
        return new Fixture(plan, run, receipt);
    }

    private static String hash(String value) {
        return SchematicProofPlan.hash(value);
    }

    private record Fixture(
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        ReplayReceipt receipt
    ) {}
}
