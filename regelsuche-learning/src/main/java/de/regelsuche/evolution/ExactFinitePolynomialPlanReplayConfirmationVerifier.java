package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.VerifiedArtifactBytes;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.VerifiedReplayReceiptArtifact;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Confirms a complete exact finite polynomial replay from independently
 * retained plan-run and replay-receipt evidence.
 *
 * <p>The supplied typed plan run is deliberately treated as untrusted. It
 * becomes usable only when its canonical bytes equal the separately loaded,
 * content-addressed plan-run bytes exactly. The current replay verifier then
 * executes the frozen formation inputs again and must reproduce the retained
 * receipt byte for byte.</p>
 *
 * <p>This verifier does not replay primitive rewrite steps, compile an
 * executable rewrite program, establish formal proof or authorize promotion.</p>
 */
public final class ExactFinitePolynomialPlanReplayConfirmationVerifier {
    public static final String VERIFIER_ID =
        "regelsuche.exact-finite-polynomial-plan-replay-confirmation-verifier/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            VERIFIER_ID,
            ExactFinitePolynomialPlanReplayArtifactVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayArtifactVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanRun.SCHEMA,
            ExactFinitePolynomialPlanRun.ARTIFACT_REVISION_HASH,
            ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayReceiptArtifactVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanReplayVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            "typed-plan-run-is-untrusted-until-canonical-byte-equality",
            "receipt-plan-run-and-frozen-input-cross-binding",
            "one-complete-current-resolver-and-solver-rerun",
            "sealed-verifier-owned-non-executable-confirmation"));

    private static final int EXACT_REPLAY_EXECUTIONS = 1;

    private final ExactFinitePolynomialPlanReplayVerifier replayVerifier =
        new ExactFinitePolynomialPlanReplayVerifier();

    public ConfirmedReplay verify(
        VerifiedReplayReceiptArtifact receiptArtifact,
        VerifiedArtifactBytes planRunArtifact,
        ExactFinitePolynomialPlanRun untrustedPlanRun,
        SchematicProofPlan plan,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> domains,
        int retainedSolutionLimit
    ) {
        Objects.requireNonNull(receiptArtifact, "receiptArtifact");
        Objects.requireNonNull(planRunArtifact, "planRunArtifact");
        Objects.requireNonNull(untrustedPlanRun, "untrustedPlanRun");
        Objects.requireNonNull(plan, "plan");
        sourceExpression = Objects.requireNonNull(
            sourceExpression,
            "sourceExpression");
        ansatzTemplate = Objects.requireNonNull(
            ansatzTemplate,
            "ansatzTemplate");
        domains = List.copyOf(Objects.requireNonNull(domains, "domains"));

        requireReceiptReference(receiptArtifact.reference());
        requirePlanRunReference(planRunArtifact.reference());
        requireCanonicalPlanRunBytes(planRunArtifact, untrustedPlanRun);

        List<String> candidateHashes = candidateHashes(untrustedPlanRun);
        requireReceiptMatchesRun(
            receiptArtifact,
            plan,
            untrustedPlanRun,
            candidateHashes);

        ReplayReceipt replayedReceipt = replayVerifier.verify(
            plan,
            sourceExpression,
            ansatzTemplate,
            domains,
            retainedSolutionLimit,
            untrustedPlanRun);
        if (!receiptArtifact.matches(replayedReceipt)) {
            throw new IllegalArgumentException(
                "current exact replay does not reproduce the retained receipt");
        }

        var result = untrustedPlanRun.solverResult();
        String confirmationHash = confirmationHash(
            receiptArtifact.reference(),
            planRunArtifact.reference(),
            receiptArtifact.verificationHash(),
            plan.contentHash(),
            untrustedPlanRun.contentHash(),
            result.contentHash(),
            result.solverRevisionHash(),
            untrustedPlanRun.status(),
            result.totalAssignments(),
            result.evaluatedAssignments(),
            result.matchingAssignments(),
            candidateHashes,
            EXACT_REPLAY_EXECUTIONS,
            replayedReceipt.contentHash());
        return new VerifiedConfirmation(
            receiptArtifact.reference(),
            planRunArtifact.reference(),
            receiptArtifact.verificationHash(),
            plan.contentHash(),
            untrustedPlanRun.contentHash(),
            result.contentHash(),
            result.solverRevisionHash(),
            untrustedPlanRun.status(),
            result.totalAssignments(),
            result.evaluatedAssignments(),
            result.matchingAssignments(),
            candidateHashes,
            EXACT_REPLAY_EXECUTIONS,
            receiptArtifact.receiptContentHash(),
            confirmationHash);
    }

    private static void requireCanonicalPlanRunBytes(
        VerifiedArtifactBytes planRunArtifact,
        ExactFinitePolynomialPlanRun untrustedPlanRun
    ) {
        String canonicalJson = untrustedPlanRun.toCanonicalJson();
        byte[] canonicalBytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(canonicalBytes, planRunArtifact.copyBytes())
                || !canonicalJson.equals(planRunArtifact.utf8())) {
            throw new IllegalArgumentException(
                "typed plan run differs from independently loaded bytes");
        }
        ArtifactReference expected = ArtifactReference.describe(
            ExactFinitePolynomialPlanReplayArtifactVerifier.PLAN_RUN_ROLE,
            ExactFinitePolynomialPlanRun.SCHEMA,
            ExactFinitePolynomialPlanReplayArtifactVerifier
                .PLAN_RUN_MEDIA_TYPE,
            canonicalBytes);
        if (!expected.equals(planRunArtifact.reference())) {
            throw new IllegalArgumentException(
                "typed plan run differs from its artifact reference");
        }
    }

    private static void requireReceiptMatchesRun(
        VerifiedReplayReceiptArtifact receipt,
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        List<String> candidateHashes
    ) {
        var result = run.solverResult();
        if (!plan.contentHash().equals(run.planHash())
                || !plan.contentHash().equals(receipt.planHash())
                || !run.contentHash().equals(receipt.planRunHash())
                || !result.contentHash().equals(
                    receipt.solverResultHash())
                || !result.solverRevisionHash().equals(
                    receipt.solverRevisionHash())
                || run.status() != receipt.runStatus()
                || result.totalAssignments()
                    != receipt.totalAssignments()
                || result.evaluatedAssignments()
                    != receipt.evaluatedAssignments()
                || result.matchingAssignments()
                    != receipt.matchingAssignments()
                || candidateHashes.size()
                    != receipt.retainedSolutions()
                || !candidateHashes.equals(
                    receipt.resolvedCandidateHashes())) {
            throw new IllegalArgumentException(
                "retained receipt differs from the byte-bound plan run");
        }
    }

    private static String confirmationHash(
        ArtifactReference receiptReference,
        ArtifactReference planRunReference,
        String receiptVerificationHash,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        List<String> candidateHashes,
        int exactReplayExecutions,
        String receiptContentHash
    ) {
        StringBuilder material = new StringBuilder();
        append(material, VERIFIER_ID);
        append(material, REVISION_HASH);
        append(material, receiptReference.artifactId());
        append(material, receiptReference.byteHash());
        append(material, receiptVerificationHash);
        append(material, planRunReference.artifactId());
        append(material, planRunReference.byteHash());
        append(material, planHash);
        append(material, planRunHash);
        append(material, solverResultHash);
        append(material, solverRevisionHash);
        append(material, runStatus.name());
        append(material, Long.toString(totalAssignments));
        append(material, Long.toString(evaluatedAssignments));
        append(material, Long.toString(matchingAssignments));
        append(material, Integer.toString(candidateHashes.size()));
        candidateHashes.forEach(value -> append(material, value));
        append(material, Integer.toString(exactReplayExecutions));
        append(material, receiptContentHash);
        return SchematicProofPlan.hash(material.toString());
    }

    private static List<String> candidateHashes(
        ExactFinitePolynomialPlanRun run
    ) {
        return run.candidates().stream()
            .map(ExactFinitePolynomialResolvedCandidate::contentHash)
            .sorted(Comparator.naturalOrder())
            .toList();
    }

    private static void requireReceiptReference(
        ArtifactReference reference
    ) {
        Objects.requireNonNull(reference, "receipt reference");
        if (!ExactFinitePolynomialPlanReplayArtifactVerifier.REFERENCE_SCHEMA
                .equals(reference.referenceSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier.RECEIPT_ROLE
                    .equals(reference.role())
                || !ReplayReceipt.SCHEMA.equals(reference.contentSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .RECEIPT_MEDIA_TYPE.equals(reference.mediaType())) {
            throw new IllegalArgumentException(
                "semantic receipt has the wrong artifact reference");
        }
    }

    private static void requirePlanRunReference(
        ArtifactReference reference
    ) {
        Objects.requireNonNull(reference, "plan-run reference");
        if (!ExactFinitePolynomialPlanReplayArtifactVerifier.REFERENCE_SCHEMA
                .equals(reference.referenceSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .PLAN_RUN_ROLE.equals(reference.role())
                || !ExactFinitePolynomialPlanRun.SCHEMA.equals(
                    reference.contentSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .PLAN_RUN_MEDIA_TYPE.equals(reference.mediaType())) {
            throw new IllegalArgumentException(
                "verified bytes are not an exact plan-run artifact");
        }
    }

    private static void validateStatusCounts(
        ExactFinitePolynomialPlanRun.Status status,
        long matchingAssignments,
        int retainedSolutions
    ) {
        boolean valid = switch (status) {
            case COMPLETE_WITHOUT_SOLUTION ->
                matchingAssignments == 0 && retainedSolutions == 0;
            case COMPLETE_WITH_RESOLUTIONS ->
                matchingAssignments > 0
                    && matchingAssignments == retainedSolutions;
            case COMPLETE_RESOLUTION_SET_TRUNCATED ->
                matchingAssignments > retainedSolutions
                    && retainedSolutions > 0;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                "confirmation status differs from assignment counts");
        }
    }

    private static List<String> normalizeCandidateHashes(
        List<String> values
    ) {
        Objects.requireNonNull(values, "resolvedCandidateHashes");
        List<String> normalized = values.stream()
            .map(value -> SchematicProofPlan.requireSha256(
                value,
                "resolved candidate hash"))
            .toList();
        if (new HashSet<>(normalized).size() != normalized.size()) {
            throw new IllegalArgumentException(
                "resolved candidate hashes must be unique");
        }
        List<String> sorted = normalized.stream()
            .sorted(Comparator.naturalOrder())
            .toList();
        if (!normalized.equals(sorted)) {
            throw new IllegalArgumentException(
                "resolved candidate hashes must be canonically ordered");
        }
        return List.copyOf(normalized);
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            append(result, value);
        }
        return result.toString();
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':')
            .append(value);
    }

    public sealed interface ConfirmedReplay permits VerifiedConfirmation {
        ArtifactReference receiptReference();

        ArtifactReference planRunReference();

        String receiptVerificationHash();

        String planHash();

        String planRunHash();

        String solverResultHash();

        String solverRevisionHash();

        ExactFinitePolynomialPlanRun.Status runStatus();

        long totalAssignments();

        long evaluatedAssignments();

        long matchingAssignments();

        List<String> resolvedCandidateHashes();

        int exactReplayExecutions();

        String receiptContentHash();

        String confirmationHash();

        default int retainedSolutions() {
            return resolvedCandidateHashes().size();
        }

        boolean matches(
            ReplayReceipt receipt,
            ExactFinitePolynomialPlanRun planRun);
    }

    private record VerifiedConfirmation(
        ArtifactReference receiptReference,
        ArtifactReference planRunReference,
        String receiptVerificationHash,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        List<String> resolvedCandidateHashes,
        int exactReplayExecutions,
        String receiptContentHash,
        String confirmationHash
    ) implements ConfirmedReplay {
        private VerifiedConfirmation {
            requireReceiptReference(receiptReference);
            requirePlanRunReference(planRunReference);
            receiptVerificationHash = SchematicProofPlan.requireSha256(
                receiptVerificationHash,
                "receiptVerificationHash");
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
            resolvedCandidateHashes = normalizeCandidateHashes(
                resolvedCandidateHashes);
            validateStatusCounts(
                runStatus,
                matchingAssignments,
                resolvedCandidateHashes.size());
            if (exactReplayExecutions != EXACT_REPLAY_EXECUTIONS) {
                throw new IllegalArgumentException(
                    "confirmation requires one complete exact replay");
            }
            receiptContentHash = SchematicProofPlan.requireSha256(
                receiptContentHash,
                "receiptContentHash");
            confirmationHash = SchematicProofPlan.requireSha256(
                confirmationHash,
                "confirmationHash");
            String expectedConfirmationHash =
                ExactFinitePolynomialPlanReplayConfirmationVerifier
                    .confirmationHash(
                        receiptReference,
                        planRunReference,
                        receiptVerificationHash,
                        planHash,
                        planRunHash,
                        solverResultHash,
                        solverRevisionHash,
                        runStatus,
                        totalAssignments,
                        evaluatedAssignments,
                        matchingAssignments,
                        resolvedCandidateHashes,
                        exactReplayExecutions,
                        receiptContentHash);
            if (!expectedConfirmationHash.equals(confirmationHash)) {
                throw new IllegalArgumentException(
                    "confirmationHash does not match confirmation contents");
            }
        }

        @Override
        public boolean matches(
            ReplayReceipt receipt,
            ExactFinitePolynomialPlanRun planRun
        ) {
            if (receipt == null || planRun == null) {
                return false;
            }
            var result = planRun.solverResult();
            return receiptContentHash.equals(receipt.contentHash())
                && planHash.equals(receipt.planHash())
                && planRunHash.equals(receipt.planRunHash())
                && solverResultHash.equals(receipt.solverResultHash())
                && solverRevisionHash.equals(
                    receipt.solverRevisionHash())
                && runStatus == receipt.runStatus()
                && totalAssignments == receipt.totalAssignments()
                && evaluatedAssignments == receipt.evaluatedAssignments()
                && matchingAssignments == receipt.matchingAssignments()
                && resolvedCandidateHashes.equals(
                    receipt.resolvedCandidateHashes())
                && planHash.equals(planRun.planHash())
                && planRunHash.equals(planRun.contentHash())
                && solverResultHash.equals(result.contentHash())
                && solverRevisionHash.equals(
                    result.solverRevisionHash())
                && runStatus == planRun.status()
                && totalAssignments == result.totalAssignments()
                && evaluatedAssignments == result.evaluatedAssignments()
                && matchingAssignments == result.matchingAssignments()
                && resolvedCandidateHashes.equals(
                    candidateHashes(planRun));
        }
    }
}
