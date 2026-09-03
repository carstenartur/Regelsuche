package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayConfirmationVerifier.VerifiedReplayConfirmation;
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
class ExactFinitePolynomialPlanReplayConfirmationVerifierTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayVerifier replayVerifier =
        new ExactFinitePolynomialPlanReplayVerifier();
    private final ExactFinitePolynomialPlanReplayArtifactVerifier byteVerifier =
        new ExactFinitePolynomialPlanReplayArtifactVerifier();
    private final ExactFinitePolynomialPlanReplayReceiptArtifactVerifier
        receiptVerifier =
            new ExactFinitePolynomialPlanReplayReceiptArtifactVerifier();
    private final ExactFinitePolynomialPlanReplayConfirmationVerifier
        confirmationVerifier =
            new ExactFinitePolynomialPlanReplayConfirmationVerifier();

    @Test
    void confirmsIndependentlyRetainedPlanRunByTwoExactExecutions() {
        Fixture fixture = fixture(
            "retained-plan-run",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);

        VerifiedReplayConfirmation confirmation = confirm(fixture);

        assertEquals(
            verifiedReceipt(fixture).reference(),
            confirmation.receiptReference());
        assertEquals(
            verifiedPlanRun(fixture.run()).reference(),
            confirmation.planRunReference());
        assertEquals(
            fixture.plan().contentHash(),
            confirmation.planHash());
        assertEquals(
            fixture.run().contentHash(),
            confirmation.planRunHash());
        assertEquals(
            fixture.run().solverResult().contentHash(),
            confirmation.solverResultHash());
        assertEquals(
            fixture.run().solverResult().solverRevisionHash(),
            confirmation.solverRevisionHash());
        assertEquals(fixture.run().status(), confirmation.runStatus());
        assertEquals(
            fixture.receipt().contentHash(),
            confirmation.replayReceiptContentHash());
        assertEquals(
            ExactFinitePolynomialPlanRunArtifactCodec
                .artifactContentHash(fixture.run()),
            confirmation.planRunArtifactContentHash());
        assertTrue(confirmation.confirmationHash().startsWith("sha256:"));
        assertEquals(
            confirmation.confirmationHash(),
            confirm(fixture).confirmationHash());

        String json =
            ExactFinitePolynomialPlanRunArtifactCodec.toCanonicalJson(
                fixture.run());
        assertTrue(json.contains(fixture.run().contentHash()));
        assertTrue(json.contains("\"holeDomains\""));
        assertTrue(json.contains("\"solutions\""));
        assertTrue(json.contains("\"resolvedCandidates\""));
        assertTrue(json.contains("\"artifactContentHash\""));
        assertFalse(json.contains("targetExpression"));
        assertFalse(json.contains("historical"));
    }

    @Test
    void confirmsCompleteNullAndTruncatedPlanRuns() {
        Fixture none = fixture(
            "retained-null-plan-run",
            "x^2 + 1",
            "(x + ${shift})^2",
            List.of(HoleDomain.integerRange("shift", -2, 2)),
            4);
        VerifiedReplayConfirmation noSolution = confirm(none);
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION,
            noSolution.runStatus());
        assertEquals(0, noSolution.matchingAssignments());
        assertTrue(noSolution.resolvedCandidateHashes().isEmpty());

        Fixture truncated = fixture(
            "retained-truncated-plan-run",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            1);
        VerifiedReplayConfirmation limited = confirm(truncated);
        assertEquals(
            ExactFinitePolynomialPlanRun.Status
                .COMPLETE_RESOLUTION_SET_TRUNCATED,
            limited.runStatus());
        assertEquals(2, limited.matchingAssignments());
        assertEquals(1, limited.resolvedCandidateHashes().size());
    }

    @Test
    void byteValidButChangedPlanRunJsonFailsAtConfirmationBoundary() {
        Fixture fixture = fixture(
            "changed-plan-run",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        String original =
            ExactFinitePolynomialPlanRunArtifactCodec.toCanonicalJson(
                fixture.run());
        String changed = original.replace(
            "\"runStatus\":\"COMPLETE_WITH_RESOLUTIONS\"",
            "\"runStatus\":\"COMPLETE_WITHOUT_SOLUTION\"");
        assertNotEquals(original, changed);

        VerifiedArtifactBytes changedBytes = verifiedPlanRunJson(changed);
        assertEquals(changed, changedBytes.utf8());
        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(fixture),
                changedBytes,
                fixture.plan(),
                fixture.source(),
                fixture.ansatz(),
                fixture.domains(),
                fixture.retainedSolutionLimit()));
    }

    @Test
    void rejectsReceiptPlanRunRoleAndFrozenInputSubstitution() {
        Fixture fixture = fixture(
            "substitution-plan-run",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        Fixture other = fixture(
            "other-plan-run",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);

        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(other),
                verifiedPlanRun(fixture.run()),
                fixture.plan(),
                fixture.source(),
                fixture.ansatz(),
                fixture.domains(),
                fixture.retainedSolutionLimit()));

        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(fixture),
                verifiedReceiptBytes(fixture.receipt()),
                fixture.plan(),
                fixture.source(),
                fixture.ansatz(),
                fixture.domains(),
                fixture.retainedSolutionLimit()));

        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(fixture),
                verifiedPlanRun(fixture.run()),
                fixture.plan(),
                "x^2 + 2",
                fixture.ansatz(),
                fixture.domains(),
                fixture.retainedSolutionLimit()));

        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(fixture),
                verifiedPlanRun(fixture.run()),
                fixture.plan(),
                fixture.source(),
                fixture.ansatz(),
                fixture.domains(),
                fixture.retainedSolutionLimit() + 1));
    }

    @Test
    void planRunByteRoleAndConfirmationConstructionRemainVerifierOwned() {
        Fixture fixture = fixture(
            "sealed-plan-run",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        ArtifactReference reference = byteVerifier.describePlanRun(
            fixture.run());
        assertEquals(
            ExactFinitePolynomialPlanReplayArtifactVerifier.PLAN_RUN_ROLE,
            reference.role());
        assertEquals(
            ExactFinitePolynomialPlanRunArtifactCodec.SCHEMA,
            reference.contentSchema());
        assertEquals(
            ExactFinitePolynomialPlanReplayArtifactVerifier
                .PLAN_RUN_MEDIA_TYPE,
            reference.mediaType());
        assertThrows(
            IllegalArgumentException.class,
            () -> byteVerifier.verifyReceipt(
                reference,
                ignored -> new LoadedArtifact(
                    reference.artifactId(),
                    ExactFinitePolynomialPlanRunArtifactCodec
                        .toCanonicalJson(fixture.run())
                        .getBytes(StandardCharsets.UTF_8))));

        assertTrue(VerifiedReplayConfirmation.class.isSealed());
        Class<?>[] permitted =
            VerifiedReplayConfirmation.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertTrue(Modifier.isPrivate(permitted[0].getModifiers()));
        assertTrue(Arrays.stream(permitted[0].getDeclaredConstructors())
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())));
    }

    private VerifiedReplayConfirmation confirm(Fixture fixture) {
        return confirmationVerifier.verify(
            verifiedReceipt(fixture),
            verifiedPlanRun(fixture.run()),
            fixture.plan(),
            fixture.source(),
            fixture.ansatz(),
            fixture.domains(),
            fixture.retainedSolutionLimit());
    }

    private VerifiedReplayReceiptArtifact verifiedReceipt(
        Fixture fixture
    ) {
        return receiptVerifier.verify(
            verifiedReceiptBytes(fixture.receipt()));
    }

    private VerifiedArtifactBytes verifiedReceiptBytes(
        ReplayReceipt receipt
    ) {
        ArtifactReference reference = byteVerifier.describeReceipt(receipt);
        byte[] bytes = receipt.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
        return byteVerifier.verifyReceipt(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));
    }

    private VerifiedArtifactBytes verifiedPlanRun(
        ExactFinitePolynomialPlanRun run
    ) {
        ArtifactReference reference = byteVerifier.describePlanRun(run);
        byte[] bytes =
            ExactFinitePolynomialPlanRunArtifactCodec.toCanonicalJson(run)
                .getBytes(StandardCharsets.UTF_8);
        return byteVerifier.verifyPlanRun(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));
    }

    private VerifiedArtifactBytes verifiedPlanRunJson(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ArtifactReference reference = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRunArtifactCodec.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier
                .PLAN_RUN_MEDIA_TYPE,
            bytes);
        return byteVerifier.verifyPlanRun(
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
        return new Fixture(
            source,
            ansatz,
            List.copyOf(domains),
            retainedSolutionLimit,
            plan,
            run,
            receipt);
    }

    private record Fixture(
        String source,
        String ansatz,
        List<HoleDomain> domains,
        int retainedSolutionLimit,
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        ReplayReceipt receipt
    ) {}
}
