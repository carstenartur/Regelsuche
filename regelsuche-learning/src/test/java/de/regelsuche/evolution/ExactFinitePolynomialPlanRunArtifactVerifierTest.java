package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialPlanRunArtifactVerifierTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayArtifactVerifier verifier =
        new ExactFinitePolynomialPlanReplayArtifactVerifier();

    @Test
    void verifiesPlanRunBytesAndRetainsAnImmutableSnapshot() {
        ExactFinitePolynomialPlanRun run = run(
            "plan-run-byte-boundary",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        ArtifactReference reference = verifier.describePlanRun(run);
        byte[] sourceBytes = run.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
        byte[] expectedBytes = sourceBytes.clone();

        VerifiedArtifactBytes verified = verifier.verifyPlanRun(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), sourceBytes));
        sourceBytes[0] = '[';
        byte[] callerCopy = verified.copyBytes();
        callerCopy[0] = '[';

        assertEquals(reference, verified.reference());
        assertEquals(expectedBytes.length, verified.byteLength());
        assertArrayEquals(expectedBytes, verified.copyBytes());
        assertEquals(run.toCanonicalJson(), verified.utf8());
        assertEquals(
            ExactFinitePolynomialPlanRun.SCHEMA,
            reference.contentSchema());
        assertEquals(
            ExactFinitePolynomialPlanReplayArtifactVerifier.PLAN_RUN_ROLE,
            reference.role());
    }

    @Test
    void canonicalProjectionRetainsDomainsSolutionsAndResolutionLinks() {
        ExactFinitePolynomialPlanRun run = run(
            "plan-run-canonical-projection",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            2);

        String json = run.toCanonicalJson();

        assertTrue(json.startsWith(
            "{\"schema\":\""
                + ExactFinitePolynomialPlanRun.SCHEMA
                + "\""));
        assertTrue(json.contains(
            "\"artifactRevisionHash\":\""
                + ExactFinitePolynomialPlanRun.ARTIFACT_REVISION_HASH
                + "\""));
        assertTrue(json.contains("\"holeDomains\":["));
        assertTrue(json.contains("\"kind\":\"SIGN\""));
        assertTrue(json.contains("\"solutions\":["));
        assertTrue(json.contains("\"solutionHash\":\"sha256:"));
        assertTrue(json.contains("\"resolvedCandidates\":["));
        assertTrue(json.contains("\"resolution\":{"));
        assertTrue(json.contains("\"checkerExecutionHash\":\"sha256:"));
        assertTrue(json.contains(
            "\"planRunHash\":\"" + run.contentHash() + "\""));
        assertEquals(json, run.toCanonicalJson());
    }

    @Test
    void rejectsWrongReturnedKeyChangedBytesAndNonCanonicalFraming() {
        ExactFinitePolynomialPlanRun run = run(
            "plan-run-byte-rejections",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        ArtifactReference reference = verifier.describePlanRun(run);
        byte[] bytes = run.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);

        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.verifyPlanRun(
                reference,
                ignored -> new LoadedArtifact(
                    SchematicProofPlan.hash("wrong-key"),
                    bytes)));

        byte[] changed = bytes.clone();
        changed[changed.length - 2] = changed[changed.length - 2] == '0'
            ? (byte) '1'
            : (byte) '0';
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.verifyPlanRun(
                reference,
                ignored -> new LoadedArtifact(
                    reference.artifactId(),
                    changed)));

        assertRejectedFraming(
            (" " + run.toCanonicalJson())
                .getBytes(StandardCharsets.UTF_8));
        assertRejectedFraming(
            (run.toCanonicalJson() + "\n")
                .getBytes(StandardCharsets.UTF_8));
        assertRejectedFraming(new byte[] {
            (byte) 0xef,
            (byte) 0xbb,
            (byte) 0xbf,
            '{',
            '}'
        });
    }

    @Test
    void keepsIdenticalBytesAtDistinctReceiptAndPlanRunAddresses() {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        ArtifactReference receipt = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE,
            ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_MEDIA_TYPE,
            bytes);
        ArtifactReference planRun = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRun.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier
                .PLAN_RUN_MEDIA_TYPE,
            bytes);

        assertNotEquals(receipt.artifactId(), planRun.artifactId());
        assertEquals(receipt.byteHash(), planRun.byteHash());
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.verifyReceipt(
                planRun,
                ignored -> new LoadedArtifact(
                    planRun.artifactId(),
                    bytes)));
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.verifyPlanRun(
                receipt,
                ignored -> new LoadedArtifact(
                    receipt.artifactId(),
                    bytes)));
    }

    @Test
    void differentValidatedRunsHaveDifferentCanonicalAddresses() {
        ExactFinitePolynomialPlanRun first = run(
            "first-plan-run-address",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        ExactFinitePolynomialPlanRun second = run(
            "second-plan-run-address",
            "x^2 + 2",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 3)),
            4);

        assertNotEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertNotEquals(
            verifier.describePlanRun(first).artifactId(),
            verifier.describePlanRun(second).artifactId());
    }

    private void assertRejectedFraming(byte[] bytes) {
        ArtifactReference reference = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRun.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier
                .PLAN_RUN_MEDIA_TYPE,
            bytes);
        assertThrows(
            IllegalArgumentException.class,
            () -> verifier.verifyPlanRun(
                reference,
                ignored -> new LoadedArtifact(
                    reference.artifactId(),
                    bytes)));
    }

    private ExactFinitePolynomialPlanRun run(
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
        return resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit);
    }
}
