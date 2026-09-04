package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.LoadedArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayConfirmationVerifier.ConfirmedReplay;
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
    void confirmsAReplayFromTwoIndependentlyLoadedArtifacts() {
        Fixture fixture = fixture(
            "independent-plan-run",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);

        VerifiedReplayReceiptArtifact receipt = verifiedReceipt(fixture);
        VerifiedArtifactBytes planRun = verifiedPlanRun(fixture.run());
        ConfirmedReplay confirmation = confirmationVerifier.verify(
            receipt,
            planRun,
            fixture.run(),
            fixture.plan(),
            fixture.source(),
            fixture.ansatz(),
            fixture.domains(),
            fixture.retainedSolutionLimit());

        var result = fixture.run().solverResult();
        assertEquals(receipt.reference(), confirmation.receiptReference());
        assertEquals(planRun.reference(), confirmation.planRunReference());
        assertEquals(
            receipt.verificationHash(),
            confirmation.receiptVerificationHash());
        assertEquals(fixture.plan().contentHash(), confirmation.planHash());
        assertEquals(fixture.run().contentHash(), confirmation.planRunHash());
        assertEquals(
            result.contentHash(),
            confirmation.solverResultHash());
        assertEquals(
            result.solverRevisionHash(),
            confirmation.solverRevisionHash());
        assertEquals(fixture.run().status(), confirmation.runStatus());
        assertEquals(
            result.totalAssignments(),
            confirmation.totalAssignments());
        assertEquals(
            result.evaluatedAssignments(),
            confirmation.evaluatedAssignments());
        assertEquals(
            result.matchingAssignments(),
            confirmation.matchingAssignments());
        assertEquals(
            fixture.run().candidates().stream()
                .map(ExactFinitePolynomialResolvedCandidate::contentHash)
                .sorted()
                .toList(),
            confirmation.resolvedCandidateHashes());
        assertEquals(
            confirmation.resolvedCandidateHashes().size(),
            confirmation.retainedSolutions());
        assertEquals(1, confirmation.exactReplayExecutions());
        assertEquals(
            fixture.receipt().contentHash(),
            confirmation.receiptContentHash());
        assertTrue(confirmation.confirmationHash().startsWith("sha256:"));
        assertEquals(
            confirmation.confirmationHash(),
            confirm(fixture).confirmationHash());
        assertTrue(confirmation.matches(fixture.receipt(), fixture.run()));
        assertFalse(confirmation.matches(null, fixture.run()));
        assertFalse(confirmation.matches(fixture.receipt(), null));
    }

    @Test
    void confirmsCompleteNullAndTruncatedRunsWithoutCollapsingThem() {
        Fixture none = fixture(
            "independent-null-run",
            "x^2 + 1",
            "(x + ${shift})^2",
            List.of(HoleDomain.integerRange("shift", -2, 2)),
            4);
        ConfirmedReplay confirmedNone = confirm(none);
        assertTrue(confirmedNone.matches(none.receipt(), none.run()));
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION,
            confirmedNone.runStatus());
        assertEquals(0, confirmedNone.matchingAssignments());
        assertTrue(confirmedNone.resolvedCandidateHashes().isEmpty());

        Fixture truncated = fixture(
            "independent-truncated-run",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            1);
        ConfirmedReplay confirmedTruncated = confirm(truncated);
        assertTrue(confirmedTruncated.matches(
            truncated.receipt(),
            truncated.run()));
        assertEquals(
            ExactFinitePolynomialPlanRun.Status
                .COMPLETE_RESOLUTION_SET_TRUNCATED,
            confirmedTruncated.runStatus());
        assertEquals(2, confirmedTruncated.matchingAssignments());
        assertEquals(1, confirmedTruncated.retainedSolutions());
    }

    @Test
    void rejectsAPlanRunObjectThatDiffersFromTheLoadedBytes() {
        Fixture retained = fixture(
            "retained-plan-run",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        Fixture substituted = fixture(
            "substituted-plan-run",
            "x^2 + 2",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 3)),
            4);

        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(retained),
                verifiedPlanRun(retained.run()),
                substituted.run(),
                retained.plan(),
                retained.source(),
                retained.ansatz(),
                retained.domains(),
                retained.retainedSolutionLimit()));
    }

    @Test
    void byteValidButSemanticallyEmptyPlanRunFailsAtConfirmation() {
        Fixture fixture = fixture(
            "empty-plan-run-object",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        ArtifactReference reference = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRun.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier
                .PLAN_RUN_MEDIA_TYPE,
            bytes);
        VerifiedArtifactBytes verified = byteVerifier.verifyPlanRun(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));

        assertEquals("{}", verified.utf8());
        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(fixture),
                verified,
                fixture.run(),
                fixture.plan(),
                fixture.source(),
                fixture.ansatz(),
                fixture.domains(),
                fixture.retainedSolutionLimit()));
    }

    @Test
    void rejectsReceiptPlanRunAndFrozenInputSubstitution() {
        Fixture retained = fixture(
            "cross-binding-retained",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        Fixture substituted = fixture(
            "cross-binding-substituted",
            "x^2 + 2",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 3)),
            4);

        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(retained),
                verifiedPlanRun(substituted.run()),
                substituted.run(),
                retained.plan(),
                retained.source(),
                retained.ansatz(),
                retained.domains(),
                retained.retainedSolutionLimit()));
        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(substituted),
                verifiedPlanRun(retained.run()),
                retained.run(),
                retained.plan(),
                retained.source(),
                retained.ansatz(),
                retained.domains(),
                retained.retainedSolutionLimit()));
        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(retained),
                verifiedPlanRun(retained.run()),
                retained.run(),
                retained.plan(),
                "x^2 + 2",
                retained.ansatz(),
                retained.domains(),
                retained.retainedSolutionLimit()));
        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(retained),
                verifiedPlanRun(retained.run()),
                retained.run(),
                retained.plan(),
                retained.source(),
                retained.ansatz(),
                List.of(HoleDomain.integerRange("constant", 0, 3)),
                retained.retainedSolutionLimit()));
        assertThrows(
            IllegalArgumentException.class,
            () -> confirmationVerifier.verify(
                verifiedReceipt(retained),
                verifiedPlanRun(retained.run()),
                retained.run(),
                retained.plan(),
                retained.source(),
                retained.ansatz(),
                retained.domains(),
                1));
    }

    @Test
    void keepsReceiptAndPlanRunArtifactRolesSeparate() {
        Fixture fixture = fixture(
            "separate-artifact-roles",
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 0, 2)),
            4);
        ArtifactReference receiptReference = byteVerifier.describeReceipt(
            fixture.receipt());
        byte[] receiptBytes = fixture.receipt().toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);

        assertThrows(
            IllegalArgumentException.class,
            () -> byteVerifier.verifyPlanRun(
                receiptReference,
                ignored -> new LoadedArtifact(
                    receiptReference.artifactId(),
                    receiptBytes)));

        ArtifactReference planRunReference = byteVerifier.describePlanRun(
            fixture.run());
        assertThrows(
            IllegalArgumentException.class,
            () -> byteVerifier.verifyReceipt(
                planRunReference,
                ignored -> new LoadedArtifact(
                    planRunReference.artifactId(),
                    fixture.run().toCanonicalJson()
                        .getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void confirmationImplementationIsSealedPrivateAndVerifierOwned() {
        assertTrue(ConfirmedReplay.class.isSealed());
        Class<?>[] permitted = ConfirmedReplay.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertTrue(Modifier.isPrivate(permitted[0].getModifiers()));
        assertTrue(Arrays.stream(permitted[0].getDeclaredConstructors())
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())));
    }

    private ConfirmedReplay confirm(Fixture fixture) {
        return confirmationVerifier.verify(
            verifiedReceipt(fixture),
            verifiedPlanRun(fixture.run()),
            fixture.run(),
            fixture.plan(),
            fixture.source(),
            fixture.ansatz(),
            fixture.domains(),
            fixture.retainedSolutionLimit());
    }

    private VerifiedReplayReceiptArtifact verifiedReceipt(Fixture fixture) {
        ArtifactReference reference = byteVerifier.describeReceipt(
            fixture.receipt());
        byte[] bytes = fixture.receipt().toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
        VerifiedArtifactBytes verified = byteVerifier.verifyReceipt(
            reference,
            ignored -> new LoadedArtifact(reference.artifactId(), bytes));
        return receiptVerifier.verify(verified);
    }

    private VerifiedArtifactBytes verifiedPlanRun(
        ExactFinitePolynomialPlanRun planRun
    ) {
        ArtifactReference reference = byteVerifier.describePlanRun(planRun);
        byte[] bytes = planRun.toCanonicalJson()
            .getBytes(StandardCharsets.UTF_8);
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
            plan,
            source,
            ansatz,
            List.copyOf(domains),
            retainedSolutionLimit,
            run,
            receipt);
    }

    private record Fixture(
        SchematicProofPlan plan,
        String source,
        String ansatz,
        List<HoleDomain> domains,
        int retainedSolutionLimit,
        ExactFinitePolynomialPlanRun run,
        ReplayReceipt receipt
    ) {}
}
