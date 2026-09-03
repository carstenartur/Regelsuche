package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.VerifiedReplayReceiptArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Confirms independently retained plan-run bytes by complete exact resolver
 * reexecution and reproduction of an already semantically verified receipt.
 *
 * <p>A successful confirmation is still not primitive rewrite evidence, an
 * executable rewrite program, formal proof or promotion authority.</p>
 */
public final class ExactFinitePolynomialPlanReplayConfirmationVerifier {
    public static final String VERIFIER_ID =
        "regelsuche.exact-finite-polynomial-plan-replay-confirmation-verifier/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            VERIFIER_ID,
            ExactFinitePolynomialPlanReplayArtifactVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayArtifactVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanRunArtifactCodec.SCHEMA,
            ExactFinitePolynomialPlanRunArtifactCodec.REVISION_HASH,
            ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            ExactFinitePolynomialPlanReplayVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            "first-complete-resolution-regenerates-plan-run-artifact",
            "receipt-and-plan-run-cross-binding",
            "second-complete-resolution-reproduces-replay-receipt",
            "sealed-verifier-owned-non-executable-confirmation"));

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayVerifier replayVerifier =
        new ExactFinitePolynomialPlanReplayVerifier();
    private final ExactFinitePolynomialPlanReplayArtifactVerifier
        artifactVerifier =
            new ExactFinitePolynomialPlanReplayArtifactVerifier();

    public VerifiedReplayConfirmation verify(
        VerifiedReplayReceiptArtifact receiptArtifact,
        VerifiedArtifactBytes planRunArtifact,
        SchematicProofPlan plan,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> holeDomains,
        int retainedSolutionLimit
    ) {
        VerifiedReplayReceiptArtifact receipt = Objects.requireNonNull(
            receiptArtifact,
            "receiptArtifact");
        VerifiedArtifactBytes retainedRun = Objects.requireNonNull(
            planRunArtifact,
            "planRunArtifact");
        SchematicProofPlan frozenPlan = Objects.requireNonNull(plan, "plan");
        requirePlanRunReference(retainedRun.reference());
        if (!frozenPlan.contentHash().equals(receipt.planHash())) {
            throw new IllegalArgumentException(
                "verified receipt is bound to a different schematic plan");
        }

        ExactFinitePolynomialPlanRun regenerated = resolver.resolve(
            frozenPlan,
            sourceExpression,
            ansatzTemplate,
            holeDomains,
            retainedSolutionLimit);
        requireReceiptMatchesRun(receipt, regenerated);

        String canonicalPlanRun =
            ExactFinitePolynomialPlanRunArtifactCodec.toCanonicalJson(
                regenerated);
        byte[] canonicalBytes = canonicalPlanRun.getBytes(
            StandardCharsets.UTF_8);
        if (!canonicalPlanRun.equals(retainedRun.utf8())
                || !Arrays.equals(
                    canonicalBytes,
                    retainedRun.copyBytes())) {
            throw new IllegalArgumentException(
                "retained plan-run bytes differ from exact regeneration");
        }
        ArtifactReference expectedReference =
            artifactVerifier.describePlanRun(regenerated);
        if (!expectedReference.equals(retainedRun.reference())) {
            throw new IllegalArgumentException(
                "retained plan-run reference differs from regeneration");
        }

        ReplayReceipt replayedReceipt = replayVerifier.verify(
            frozenPlan,
            sourceExpression,
            ansatzTemplate,
            holeDomains,
            retainedSolutionLimit,
            regenerated);
        if (!receipt.matches(replayedReceipt)) {
            throw new IllegalArgumentException(
                "second exact plan replay differs from verified receipt");
        }

        List<String> candidateHashes = candidateHashes(regenerated);
        String artifactContentHash =
            ExactFinitePolynomialPlanRunArtifactCodec.artifactContentHash(
                regenerated);
        String confirmationHash = SchematicProofPlan.hash(lengthPrefixed(
            VERIFIER_ID,
            REVISION_HASH,
            receipt.reference().artifactId(),
            receipt.verificationHash(),
            retainedRun.reference().artifactId(),
            retainedRun.reference().byteHash(),
            artifactContentHash,
            regenerated.contentHash(),
            replayedReceipt.contentHash()));
        return new ConfirmedReplay(
            receipt.reference(),
            retainedRun.reference(),
            frozenPlan.contentHash(),
            regenerated.contentHash(),
            regenerated.solverResult().contentHash(),
            regenerated.solverResult().solverRevisionHash(),
            regenerated.status(),
            regenerated.solverResult().totalAssignments(),
            regenerated.solverResult().evaluatedAssignments(),
            regenerated.solverResult().matchingAssignments(),
            candidateHashes,
            replayedReceipt.contentHash(),
            artifactContentHash,
            confirmationHash);
    }

    private static void requirePlanRunReference(
        ArtifactReference reference
    ) {
        Objects.requireNonNull(reference, "plan-run reference");
        if (!ExactFinitePolynomialPlanReplayArtifactVerifier.REFERENCE_SCHEMA
                .equals(reference.referenceSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .PLAN_RUN_ROLE.equals(reference.role())
                || !ExactFinitePolynomialPlanRunArtifactCodec.SCHEMA.equals(
                    reference.contentSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .PLAN_RUN_MEDIA_TYPE.equals(reference.mediaType())) {
            throw new IllegalArgumentException(
                "verified bytes are not an exact plan-run artifact");
        }
    }

    private static void requireReceiptMatchesRun(
        VerifiedReplayReceiptArtifact receipt,
        ExactFinitePolynomialPlanRun run
    ) {
        var result = run.solverResult();
        List<String> candidateHashes = candidateHashes(run);
        if (!receipt.planHash().equals(run.planHash())
                || !receipt.planRunHash().equals(run.contentHash())
                || !receipt.solverResultHash().equals(result.contentHash())
                || !receipt.solverRevisionHash().equals(
                    result.solverRevisionHash())
                || receipt.runStatus() != run.status()
                || receipt.totalAssignments()
                    != result.totalAssignments()
                || receipt.evaluatedAssignments()
                    != result.evaluatedAssignments()
                || receipt.matchingAssignments()
                    != result.matchingAssignments()
                || receipt.retainedSolutions() != candidateHashes.size()
                || !receipt.resolvedCandidateHashes().equals(
                    candidateHashes)) {
            throw new IllegalArgumentException(
                "verified receipt differs from regenerated plan run");
        }
    }

    private static List<String> candidateHashes(
        ExactFinitePolynomialPlanRun run
    ) {
        return run.candidates().stream()
            .map(ExactFinitePolynomialResolvedCandidate::contentHash)
            .sorted(Comparator.naturalOrder())
            .toList();
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

    public sealed interface VerifiedReplayConfirmation
            permits ConfirmedReplay {
        ArtifactReference receiptReference();

        ArtifactReference planRunReference();

        String planHash();

        String planRunHash();

        String solverResultHash();

        String solverRevisionHash();

        ExactFinitePolynomialPlanRun.Status runStatus();

        long totalAssignments();

        long evaluatedAssignments();

        long matchingAssignments();

        List<String> resolvedCandidateHashes();

        String replayReceiptContentHash();

        String planRunArtifactContentHash();

        String confirmationHash();
    }

    private record ConfirmedReplay(
        ArtifactReference receiptReference,
        ArtifactReference planRunReference,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        List<String> resolvedCandidateHashes,
        String replayReceiptContentHash,
        String planRunArtifactContentHash,
        String confirmationHash
    ) implements VerifiedReplayConfirmation {
        private ConfirmedReplay {
            receiptReference = Objects.requireNonNull(
                receiptReference,
                "receiptReference");
            planRunReference = Objects.requireNonNull(
                planRunReference,
                "planRunReference");
            planHash = SchematicProofPlan.requireSha256(
                planHash,
                "planHash");
            planRunHash = SchematicProofPlan.requireSha256(
                planRunHash,
                "planRunHash");
            solverResultHash = SchematicProofPlan.requireSha256(
                solverResultHash,
                "solverResultHash");
            solverRevisionHash = SchematicProofPlan.requireSha256(
                solverRevisionHash,
                "solverRevisionHash");
            runStatus = Objects.requireNonNull(runStatus, "runStatus");
            if (totalAssignments < 1
                    || evaluatedAssignments != totalAssignments
                    || matchingAssignments < 0
                    || matchingAssignments > evaluatedAssignments) {
                throw new IllegalArgumentException(
                    "confirmation assignment counts are inconsistent");
            }
            resolvedCandidateHashes = List.copyOf(
                Objects.requireNonNull(
                    resolvedCandidateHashes,
                    "resolvedCandidateHashes"));
            replayReceiptContentHash = SchematicProofPlan.requireSha256(
                replayReceiptContentHash,
                "replayReceiptContentHash");
            planRunArtifactContentHash =
                SchematicProofPlan.requireSha256(
                    planRunArtifactContentHash,
                    "planRunArtifactContentHash");
            confirmationHash = SchematicProofPlan.requireSha256(
                confirmationHash,
                "confirmationHash");
        }
    }
}
