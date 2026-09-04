package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.CanonicalWork;
import de.regelsuche.evolution.ExactFinitePolynomialPlanCandidateEvidenceVerifier.VerifiedCandidateEvidence;
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
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialPlanCandidateEvidenceVerifierTest {
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
    private final ExactFinitePolynomialPlanCandidateEvidenceVerifier verifier =
        new ExactFinitePolynomialPlanCandidateEvidenceVerifier();

    @Test
    void issuesDistinctEvidenceForTwoExplicitlySelectedSignCandidates() {
        Fixture fixture = fixture(
            "selected-sign-candidates",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            2);
        VerifiedReplayConfirmation confirmation = confirmation(fixture);
        List<String> hashes = candidateHashes(fixture.run());
        assertEquals(2, hashes.size());

        VerifiedCandidateEvidence first = verifier.verify(
            confirmation,
            fixture.plan(),
            fixture.run(),
            hashes.get(0));
        VerifiedCandidateEvidence second = verifier.verify(
            confirmation,
            fixture.plan(),
            fixture.run(),
            hashes.get(1));

        assertEquals(hashes.get(0), first.candidateHash());
        assertEquals(hashes.get(1), second.candidateHash());
        assertNotEquals(first.solutionHash(), second.solutionHash());
        assertNotEquals(first.resolutionHash(), second.resolutionHash());
        assertNotEquals(first.evidenceHash(), second.evidenceHash());
        assertEquals(
            ExactFinitePolynomialPlanCandidateEvidenceVerifier.THEORY_STEP_ID,
            first.theoryStepId());
        assertEquals(
            fixture.run().solverResult().sourceExpression(),
            first.sourceExpression());
        assertNotEquals(
            first.sourceExpression(),
            first.transformedExpression());
        assertTrue(first.assumptions().isEmpty());
        assertEquals(2, first.canonicalWork()
            .retainedRunAssignmentEvaluations());
        assertEquals(2, first.canonicalWork()
            .confirmationRegenerationAssignmentEvaluations());
        assertEquals(2, first.canonicalWork()
            .confirmationReplayAssignmentEvaluations());
        assertEquals(2, first.canonicalWork().retainedCandidateCount());
        assertEquals(4, first.canonicalWork()
            .candidateIdentityComparisons());
        assertEquals(10, first.canonicalWork().totalWorkUnits());

        String json = first.toCanonicalJson();
        assertTrue(json.contains(first.evidenceHash()));
        assertTrue(json.contains(first.candidateHash()));
        assertTrue(json.contains(first.confirmationHash()));
        assertFalse(json.contains("primitiveRuleIds"));
        assertFalse(json.contains("RewriteProgram"));
        assertFalse(json.contains("targetExpression"));
        assertEquals(json, verifier.verify(
            confirmation,
            fixture.plan(),
            fixture.run(),
            hashes.get(0)).toCanonicalJson());
    }

    @Test
    void rejectsUnknownStaleAndCrossRunCandidateSelections() {
        Fixture fixture = fixture(
            "selected-candidate-primary",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            2);
        Fixture other = fixture(
            "selected-candidate-other",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            2);
        VerifiedReplayConfirmation confirmation = confirmation(fixture);
        String own = candidateHashes(fixture.run()).getFirst();
        String stale = candidateHashes(other.run()).getFirst();

        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            confirmation,
            fixture.plan(),
            fixture.run(),
            SchematicProofPlan.hash("unknown-candidate")));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            confirmation,
            fixture.plan(),
            fixture.run(),
            stale));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            confirmation(other),
            fixture.plan(),
            fixture.run(),
            own));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            confirmation,
            other.plan(),
            other.run(),
            stale));
    }

    @Test
    void noSolutionRunCannotIssueTransformationEvidence() {
        Fixture fixture = fixture(
            "selected-candidate-no-solution",
            "x^2 + 1",
            "(x + ${shift})^2",
            List.of(HoleDomain.integerRange("shift", -2, 2)),
            4);
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION,
            fixture.run().status());
        assertTrue(fixture.run().candidates().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            confirmation(fixture),
            fixture.plan(),
            fixture.run(),
            SchematicProofPlan.hash("no-solution-candidate")));
    }

    @Test
    void truncatedRunIssuesEvidenceOnlyForItsRetainedCandidate() {
        Fixture truncated = fixture(
            "selected-candidate-truncated",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            1);
        Fixture complete = fixture(
            "selected-candidate-complete-control",
            "x^2",
            "(${sign}*x)^2",
            List.of(HoleDomain.signs("sign")),
            2);
        String retained = candidateHashes(truncated.run()).getFirst();
        VerifiedCandidateEvidence evidence = verifier.verify(
            confirmation(truncated),
            truncated.plan(),
            truncated.run(),
            retained);

        assertEquals(
            ExactFinitePolynomialPlanRun.Status
                .COMPLETE_RESOLUTION_SET_TRUNCATED,
            evidence.runStatus());
        assertEquals(1, evidence.canonicalWork().retainedCandidateCount());
        assertEquals(2, evidence.canonicalWork()
            .candidateIdentityComparisons());
        assertEquals(8, evidence.canonicalWork().totalWorkUnits());

        String notRetainedHere = candidateHashes(complete.run()).getLast();
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            confirmation(truncated),
            truncated.plan(),
            truncated.run(),
            notRetainedHere));
    }

    @Test
    void canonicalWorkAndEvidenceConstructionFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
            new CanonicalWork(2, 2, 2, 1, 1, 7));
        assertThrows(IllegalArgumentException.class, () ->
            new CanonicalWork(2, 1, 2, 1, 2, 7));
        assertThrows(ArithmeticException.class, () ->
            new CanonicalWork(
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                1,
                2,
                Long.MAX_VALUE));

        assertTrue(VerifiedCandidateEvidence.class.isSealed());
        Class<?>[] permitted =
            VerifiedCandidateEvidence.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertTrue(Modifier.isPrivate(permitted[0].getModifiers()));
        assertTrue(Arrays.stream(permitted[0].getDeclaredConstructors())
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())));
        assertTrue(Arrays.stream(
                VerifiedCandidateEvidence.class.getDeclaredMethods())
            .noneMatch(method -> method.getReturnType().getName()
                    .contains("Transformation")
                || Arrays.stream(method.getParameterTypes())
                    .anyMatch(type -> type.getName()
                        .contains("Transformation"))));
    }

    private VerifiedReplayConfirmation confirmation(Fixture fixture) {
        VerifiedReplayReceiptArtifact receipt = receiptVerifier.verify(
            verifiedReceiptBytes(fixture.receipt()));
        return confirmationVerifier.verify(
            receipt,
            verifiedPlanRun(fixture.run()),
            fixture.plan(),
            fixture.source(),
            fixture.ansatz(),
            fixture.domains(),
            fixture.retainedSolutionLimit());
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

    private static List<String> candidateHashes(
        ExactFinitePolynomialPlanRun run
    ) {
        return run.candidates().stream()
            .map(ExactFinitePolynomialResolvedCandidate::contentHash)
            .sorted(Comparator.naturalOrder())
            .toList();
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
