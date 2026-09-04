package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayConfirmationVerifier.VerifiedReplayConfirmation;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Issues non-executable evidence for one explicitly selected candidate from a
 * confirmed exact finite polynomial plan run.
 *
 * <p>The selected candidate is one named exact-theory step. This verifier does
 * not invent a sequence of primitive AST rewrites, expose a transformation
 * engine, compile a rewrite program or authorize promotion.</p>
 */
public final class ExactFinitePolynomialPlanCandidateEvidenceVerifier {
    public static final String EVIDENCE_SCHEMA =
        "regelsuche.exact-finite-polynomial-plan-candidate-evidence/v1";
    public static final String VERIFIER_ID =
        "regelsuche.exact-finite-polynomial-plan-candidate-evidence-verifier/v1";
    public static final String THEORY_STEP_ID =
        "regelsuche.exact-finite-polynomial-plan-candidate-equivalence/v1";
    public static final int MAX_EVIDENCE_BYTES = 1_000_000;
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            EVIDENCE_SCHEMA,
            VERIFIER_ID,
            THEORY_STEP_ID,
            ExactFinitePolynomialPlanReplayConfirmationVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayConfirmationVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanRunArtifactCodec.SCHEMA,
            ExactFinitePolynomialPlanRunArtifactCodec.REVISION_HASH,
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLUTION_IDENTITY_REVISION,
            "explicit-candidate-content-hash-selection",
            "complete-confirmation-run-and-candidate-cross-binding",
            "three-exact-run-assignment-ledgers-and-two-membership-passes",
            "sealed-verifier-owned-non-executable-theory-step-evidence"));

    private final ExactFinitePolynomialPlanReplayArtifactVerifier
        artifactVerifier =
            new ExactFinitePolynomialPlanReplayArtifactVerifier();

    public VerifiedCandidateEvidence verify(
        VerifiedReplayConfirmation replayConfirmation,
        SchematicProofPlan schematicPlan,
        ExactFinitePolynomialPlanRun planRun,
        String candidateContentHash
    ) {
        VerifiedReplayConfirmation confirmation = Objects.requireNonNull(
            replayConfirmation,
            "replayConfirmation");
        SchematicProofPlan plan = Objects.requireNonNull(
            schematicPlan,
            "schematicPlan");
        ExactFinitePolynomialPlanRun run = Objects.requireNonNull(
            planRun,
            "planRun");
        String selectedHash = SchematicProofPlan.requireSha256(
            candidateContentHash,
            "candidateContentHash");

        List<String> candidateHashes = candidateHashes(run);
        requireConfirmationMatchesRun(
            confirmation,
            plan,
            run,
            candidateHashes);
        ExactFinitePolynomialResolvedCandidate candidate = selectCandidate(
            confirmation,
            run,
            selectedHash);
        requireCandidateLinks(plan, run, candidate);

        SearchResult result = run.solverResult();
        String sourceExpression = result.sourceExpression();
        String transformedExpression =
            candidate.solution().instantiatedExpression();
        if (sourceExpression.equals(transformedExpression)) {
            throw new IllegalArgumentException(
                "selected finite plan candidate does not change the expression");
        }
        if (plan.obligations().stream()
                .anyMatch(obligation -> !obligation.assumptions().isEmpty())) {
            throw new IllegalArgumentException(
                "v1 candidate evidence requires an assumption-free plan");
        }

        CanonicalWork work = CanonicalWork.fromConfirmedRun(
            result.evaluatedAssignments(),
            candidateHashes.size());
        List<String> assumptions = List.of();
        String solutionHash = candidate.solution().contentHash();
        String resolutionHash = candidate.resolution().contentHash();
        String exactNormalForm = candidate.solution().exactNormalForm();
        String payload = render(
            EVIDENCE_SCHEMA,
            VERIFIER_ID,
            REVISION_HASH,
            THEORY_STEP_ID,
            confirmation.receiptReference(),
            confirmation.planRunReference(),
            confirmation.confirmationHash(),
            plan.contentHash(),
            run.contentHash(),
            result.contentHash(),
            result.solverRevisionHash(),
            run.status(),
            selectedHash,
            solutionHash,
            resolutionHash,
            sourceExpression,
            transformedExpression,
            exactNormalForm,
            assumptions,
            work,
            null);
        requireSize(payload);
        String evidenceHash = SchematicProofPlan.hash(payload);
        return new VerifiedEvidence(
            EVIDENCE_SCHEMA,
            VERIFIER_ID,
            REVISION_HASH,
            THEORY_STEP_ID,
            confirmation.receiptReference(),
            confirmation.planRunReference(),
            confirmation.confirmationHash(),
            plan.contentHash(),
            run.contentHash(),
            result.contentHash(),
            result.solverRevisionHash(),
            run.status(),
            selectedHash,
            solutionHash,
            resolutionHash,
            sourceExpression,
            transformedExpression,
            exactNormalForm,
            assumptions,
            work,
            evidenceHash);
    }

    private void requireConfirmationMatchesRun(
        VerifiedReplayConfirmation confirmation,
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        List<String> candidateHashes
    ) {
        SearchResult result = run.solverResult();
        requireReceiptReference(confirmation.receiptReference());
        ArtifactReference expectedPlanRunReference =
            artifactVerifier.describePlanRun(run);
        if (!expectedPlanRunReference.equals(
                confirmation.planRunReference())) {
            throw new IllegalArgumentException(
                "replay confirmation is bound to another plan-run artifact");
        }
        if (!plan.contentHash().equals(run.planHash())
                || !plan.contentHash().equals(confirmation.planHash())
                || !run.contentHash().equals(confirmation.planRunHash())
                || !result.contentHash().equals(
                    confirmation.solverResultHash())
                || !result.solverRevisionHash().equals(
                    confirmation.solverRevisionHash())
                || !ExactFinitePolynomialHoleSolver.REVISION_HASH.equals(
                    result.solverRevisionHash())
                || run.status() != confirmation.runStatus()
                || result.totalAssignments()
                    != confirmation.totalAssignments()
                || result.evaluatedAssignments()
                    != confirmation.evaluatedAssignments()
                || result.matchingAssignments()
                    != confirmation.matchingAssignments()
                || !candidateHashes.equals(
                    confirmation.resolvedCandidateHashes())
                || !ExactFinitePolynomialPlanRunArtifactCodec
                    .artifactContentHash(run).equals(
                        confirmation.planRunArtifactContentHash())) {
            throw new IllegalArgumentException(
                "replay confirmation differs from the supplied plan run");
        }
        SchematicProofPlan.requireSha256(
            confirmation.confirmationHash(),
            "confirmationHash");
        SchematicProofPlan.requireSha256(
            confirmation.replayReceiptContentHash(),
            "replayReceiptContentHash");
    }

    private static void requireReceiptReference(
        ArtifactReference reference
    ) {
        Objects.requireNonNull(reference, "receiptReference");
        if (!ExactFinitePolynomialPlanReplayArtifactVerifier.REFERENCE_SCHEMA
                .equals(reference.referenceSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .RECEIPT_ROLE.equals(reference.role())
                || !ReplayReceipt.SCHEMA.equals(reference.contentSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .RECEIPT_MEDIA_TYPE.equals(reference.mediaType())) {
            throw new IllegalArgumentException(
                "confirmation receipt reference has the wrong role or schema");
        }
    }

    private static ExactFinitePolynomialResolvedCandidate selectCandidate(
        VerifiedReplayConfirmation confirmation,
        ExactFinitePolynomialPlanRun run,
        String selectedHash
    ) {
        if (run.status()
                == ExactFinitePolynomialPlanRun.Status
                    .COMPLETE_WITHOUT_SOLUTION) {
            throw new IllegalArgumentException(
                "a no-solution plan run has no candidate evidence");
        }
        long confirmedOccurrences = confirmation.resolvedCandidateHashes()
            .stream()
            .filter(selectedHash::equals)
            .count();
        List<ExactFinitePolynomialResolvedCandidate> selected =
            run.candidates().stream()
                .filter(candidate ->
                    selectedHash.equals(candidate.contentHash()))
                .toList();
        if (confirmedOccurrences != 1 || selected.size() != 1) {
            throw new IllegalArgumentException(
                "candidate hash does not select exactly one confirmed candidate");
        }
        return selected.getFirst();
    }

    private static void requireCandidateLinks(
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        ExactFinitePolynomialResolvedCandidate candidate
    ) {
        SearchResult result = run.solverResult();
        String solutionHash = candidate.solution().contentHash();
        long matchingSolutions = result.solutions().stream()
            .filter(solution -> solutionHash.equals(solution.contentHash()))
            .count();
        if (!candidate.resolution().planHash().equals(plan.contentHash())
                || !candidate.solverResultHash().equals(result.contentHash())
                || !candidate.resolution().isStructurallyCompleteFor(plan)
                || matchingSolutions != 1
                || !result.solutions().contains(candidate.solution())) {
            throw new IllegalArgumentException(
                "selected candidate links differ from plan-run authority");
        }
    }

    private static List<String> candidateHashes(
        ExactFinitePolynomialPlanRun run
    ) {
        List<String> hashes = run.candidates().stream()
            .map(ExactFinitePolynomialResolvedCandidate::contentHash)
            .map(value -> SchematicProofPlan.requireSha256(
                value,
                "resolved candidate hash"))
            .sorted(Comparator.naturalOrder())
            .toList();
        if (new HashSet<>(hashes).size() != hashes.size()) {
            throw new IllegalArgumentException(
                "plan-run candidate identities are not unique");
        }
        return List.copyOf(hashes);
    }

    private static String render(
        String schema,
        String verifierId,
        String verifierRevisionHash,
        String theoryStepId,
        ArtifactReference receiptReference,
        ArtifactReference planRunReference,
        String confirmationHash,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        String candidateHash,
        String solutionHash,
        String resolutionHash,
        String sourceExpression,
        String transformedExpression,
        String exactNormalForm,
        List<String> assumptions,
        CanonicalWork work,
        String evidenceHash
    ) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", schema)
            .property("verifierId", verifierId)
            .property("verifierRevisionHash", verifierRevisionHash)
            .property("theoryStepId", theoryStepId)
            .object("receiptReference", writer ->
                writeReference(writer, receiptReference))
            .object("planRunReference", writer ->
                writeReference(writer, planRunReference))
            .property("confirmationHash", confirmationHash)
            .property("planHash", planHash)
            .property("planRunHash", planRunHash)
            .property("solverResultHash", solverResultHash)
            .property("solverRevisionHash", solverRevisionHash)
            .property("runStatus", runStatus.name())
            .property("candidateHash", candidateHash)
            .property("solutionHash", solutionHash)
            .property("resolutionHash", resolutionHash)
            .property("sourceExpression", sourceExpression)
            .property("transformedExpression", transformedExpression)
            .property("exactNormalForm", exactNormalForm)
            .stringArray("assumptions", assumptions)
            .object("canonicalWork", writer -> writer
                .property(
                    "retainedRunAssignmentEvaluations",
                    work.retainedRunAssignmentEvaluations())
                .property(
                    "confirmationRegenerationAssignmentEvaluations",
                    work.confirmationRegenerationAssignmentEvaluations())
                .property(
                    "confirmationReplayAssignmentEvaluations",
                    work.confirmationReplayAssignmentEvaluations())
                .property(
                    "retainedCandidateCount",
                    work.retainedCandidateCount())
                .property(
                    "candidateIdentityComparisons",
                    work.candidateIdentityComparisons())
                .property("totalWorkUnits", work.totalWorkUnits()));
        if (evidenceHash != null) {
            json.property("evidenceHash", evidenceHash);
        }
        return json.endObject().toString();
    }

    private static void writeReference(
        JsonWriter writer,
        ArtifactReference reference
    ) {
        writer.property("referenceSchema", reference.referenceSchema())
            .property("artifactId", reference.artifactId())
            .property("role", reference.role())
            .property("contentSchema", reference.contentSchema())
            .property("mediaType", reference.mediaType())
            .property("byteHash", reference.byteHash())
            .property("byteLength", reference.byteLength());
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

    private static void requireSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length
                > MAX_EVIDENCE_BYTES) {
            throw new IllegalArgumentException(
                "selected candidate evidence exceeds byte limit");
        }
    }

    /**
     * Canonical work bound to this evidence. The three assignment fields retain
     * the original plan run plus both exact executions required by replay
     * confirmation. Candidate work is two complete identity membership passes.
     */
    public record CanonicalWork(
        long retainedRunAssignmentEvaluations,
        long confirmationRegenerationAssignmentEvaluations,
        long confirmationReplayAssignmentEvaluations,
        int retainedCandidateCount,
        long candidateIdentityComparisons,
        long totalWorkUnits
    ) {
        public CanonicalWork {
            if (retainedRunAssignmentEvaluations < 1
                    || confirmationRegenerationAssignmentEvaluations
                        != retainedRunAssignmentEvaluations
                    || confirmationReplayAssignmentEvaluations
                        != retainedRunAssignmentEvaluations
                    || retainedCandidateCount < 1) {
                throw new IllegalArgumentException(
                    "candidate evidence work inputs are inconsistent");
            }
            long expectedComparisons = Math.multiplyExact(
                2L,
                retainedCandidateCount);
            if (candidateIdentityComparisons != expectedComparisons) {
                throw new IllegalArgumentException(
                    "candidate identity work differs from two full passes");
            }
            long assignments = Math.addExact(
                retainedRunAssignmentEvaluations,
                Math.addExact(
                    confirmationRegenerationAssignmentEvaluations,
                    confirmationReplayAssignmentEvaluations));
            long expectedTotal = Math.addExact(
                assignments,
                candidateIdentityComparisons);
            if (totalWorkUnits != expectedTotal) {
                throw new IllegalArgumentException(
                    "candidate evidence total work is inconsistent");
            }
        }

        static CanonicalWork fromConfirmedRun(
            long evaluatedAssignments,
            int retainedCandidates
        ) {
            long comparisons = Math.multiplyExact(2L, retainedCandidates);
            long assignments = Math.multiplyExact(
                3L,
                evaluatedAssignments);
            return new CanonicalWork(
                evaluatedAssignments,
                evaluatedAssignments,
                evaluatedAssignments,
                retainedCandidates,
                comparisons,
                Math.addExact(assignments, comparisons));
        }
    }

    /**
     * Read-only evidence surface with one private permitted implementation.
     * Receiving this value confirms only the selected exact theory step.
     */
    public sealed interface VerifiedCandidateEvidence
            permits VerifiedEvidence {
        String schema();

        String verifierId();

        String verifierRevisionHash();

        String theoryStepId();

        ArtifactReference receiptReference();

        ArtifactReference planRunReference();

        String confirmationHash();

        String planHash();

        String planRunHash();

        String solverResultHash();

        String solverRevisionHash();

        ExactFinitePolynomialPlanRun.Status runStatus();

        String candidateHash();

        String solutionHash();

        String resolutionHash();

        String sourceExpression();

        String transformedExpression();

        String exactNormalForm();

        List<String> assumptions();

        CanonicalWork canonicalWork();

        String evidenceHash();

        String toCanonicalJson();
    }

    private record VerifiedEvidence(
        String schema,
        String verifierId,
        String verifierRevisionHash,
        String theoryStepId,
        ArtifactReference receiptReference,
        ArtifactReference planRunReference,
        String confirmationHash,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        String candidateHash,
        String solutionHash,
        String resolutionHash,
        String sourceExpression,
        String transformedExpression,
        String exactNormalForm,
        List<String> assumptions,
        CanonicalWork canonicalWork,
        String evidenceHash
    ) implements VerifiedCandidateEvidence {
        private VerifiedEvidence {
            if (!EVIDENCE_SCHEMA.equals(schema)
                    || !VERIFIER_ID.equals(verifierId)
                    || !REVISION_HASH.equals(verifierRevisionHash)
                    || !THEORY_STEP_ID.equals(theoryStepId)) {
                throw new IllegalArgumentException(
                    "selected candidate evidence revision is unsupported");
            }
            receiptReference = Objects.requireNonNull(
                receiptReference,
                "receiptReference");
            planRunReference = Objects.requireNonNull(
                planRunReference,
                "planRunReference");
            confirmationHash = SchematicProofPlan.requireSha256(
                confirmationHash,
                "confirmationHash");
            planHash = SchematicProofPlan.requireSha256(planHash, "planHash");
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
            candidateHash = SchematicProofPlan.requireSha256(
                candidateHash,
                "candidateHash");
            solutionHash = SchematicProofPlan.requireSha256(
                solutionHash,
                "solutionHash");
            resolutionHash = SchematicProofPlan.requireSha256(
                resolutionHash,
                "resolutionHash");
            sourceExpression = requireText(
                sourceExpression,
                "sourceExpression");
            transformedExpression = requireText(
                transformedExpression,
                "transformedExpression");
            exactNormalForm = requireText(
                exactNormalForm,
                "exactNormalForm");
            assumptions = List.copyOf(
                Objects.requireNonNull(assumptions, "assumptions"));
            if (!assumptions.isEmpty()) {
                throw new IllegalArgumentException(
                    "v1 selected candidate evidence is assumption-free");
            }
            canonicalWork = Objects.requireNonNull(
                canonicalWork,
                "canonicalWork");
            evidenceHash = SchematicProofPlan.requireSha256(
                evidenceHash,
                "evidenceHash");
            String payload = render(
                schema,
                verifierId,
                verifierRevisionHash,
                theoryStepId,
                receiptReference,
                planRunReference,
                confirmationHash,
                planHash,
                planRunHash,
                solverResultHash,
                solverRevisionHash,
                runStatus,
                candidateHash,
                solutionHash,
                resolutionHash,
                sourceExpression,
                transformedExpression,
                exactNormalForm,
                assumptions,
                canonicalWork,
                null);
            requireSize(payload);
            if (!SchematicProofPlan.hash(payload).equals(evidenceHash)) {
                throw new IllegalArgumentException(
                    "selected candidate evidence hash differs from contents");
            }
            requireSize(toCanonicalJson());
        }

        @Override
        public String toCanonicalJson() {
            return render(
                schema,
                verifierId,
                verifierRevisionHash,
                theoryStepId,
                receiptReference,
                planRunReference,
                confirmationHash,
                planHash,
                planRunHash,
                solverResultHash,
                solverRevisionHash,
                runStatus,
                candidateHash,
                solutionHash,
                resolutionHash,
                sourceExpression,
                transformedExpression,
                exactNormalForm,
                assumptions,
                canonicalWork,
                evidenceHash);
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
