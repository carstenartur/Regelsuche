package de.regelsuche.evolution;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayArtifactVerifier.ArtifactReference;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayConfirmationVerifier.ConfirmedReplay;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Issues non-executable evidence for one explicitly selected candidate from a
 * confirmed exact finite polynomial plan run.
 *
 * <p>The selected candidate is represented by one named exact-theory step.
 * This verifier does not invent primitive AST rewrites, expose a transformation
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
    private static final int CANDIDATE_IDENTITY_PASSES = 5;
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            EVIDENCE_SCHEMA,
            VERIFIER_ID,
            THEORY_STEP_ID,
            ExactFinitePolynomialPlanReplayConfirmationVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayConfirmationVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanReplayArtifactVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanReplayArtifactVerifier.REVISION_HASH,
            ExactFinitePolynomialPlanRun.SCHEMA,
            ExactFinitePolynomialPlanRun.ARTIFACT_REVISION_HASH,
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLUTION_IDENTITY_REVISION,
            Integer.toString(CANDIDATE_IDENTITY_PASSES),
            "explicit-candidate-content-hash-selection",
            "complete-confirmation-run-and-candidate-cross-binding",
            "only-confirmation-reported-replays-count-as-executions",
            "five-complete-candidate-identity-passes",
            "sealed-verifier-owned-non-executable-theory-step-evidence"));

    private final ExactFinitePolynomialPlanReplayArtifactVerifier
        artifactVerifier = new ExactFinitePolynomialPlanReplayArtifactVerifier();

    public VerifiedCandidateEvidence verify(
        ConfirmedReplay replayConfirmation,
        SchematicProofPlan schematicPlan,
        ExactFinitePolynomialPlanRun planRun,
        String candidateContentHash
    ) {
        ConfirmedReplay confirmation = Objects.requireNonNull(
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

        ArtifactReference expectedPlanRunReference =
            artifactVerifier.describePlanRun(run);
        List<String> candidateHashes = requireConfirmationMatchesRun(
            confirmation,
            plan,
            run,
            expectedPlanRunReference);
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

        EvidenceData data = new EvidenceData(
            EVIDENCE_SCHEMA,
            VERIFIER_ID,
            REVISION_HASH,
            THEORY_STEP_ID,
            confirmation.receiptReference(),
            confirmation.planRunReference(),
            confirmation.receiptVerificationHash(),
            confirmation.confirmationHash(),
            plan.contentHash(),
            run.contentHash(),
            result.contentHash(),
            result.solverRevisionHash(),
            run.status(),
            result.totalAssignments(),
            result.evaluatedAssignments(),
            result.matchingAssignments(),
            candidateHashes.size(),
            selectedHash,
            candidate.solution().contentHash(),
            candidate.resolution().contentHash(),
            sourceExpression,
            transformedExpression,
            candidate.solution().exactNormalForm(),
            List.of(),
            CanonicalWork.fromConfirmation(
                confirmation,
                result.evaluatedAssignments(),
                candidateHashes.size()));
        String payload = render(data, null);
        requireSize(payload);
        return new VerifiedEvidence(data, SchematicProofPlan.hash(payload));
    }

    /**
     * Performs the complete confirmation-equality candidate pass while taking
     * the immutable identity snapshot used by the remaining checks.
     */
    private List<String> requireConfirmationMatchesRun(
        ConfirmedReplay confirmation,
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        ArtifactReference expectedPlanRunReference
    ) {
        SearchResult result = run.solverResult();
        requireReceiptReference(confirmation.receiptReference());
        requirePlanRunReference(confirmation.planRunReference());
        if (!expectedPlanRunReference.equals(confirmation.planRunReference())) {
            throw new IllegalArgumentException(
                "replay confirmation is bound to another plan-run artifact");
        }

        List<String> confirmedHashes = confirmation.resolvedCandidateHashes();
        List<String> actualHashes = new ArrayList<>(run.candidates().size());
        boolean candidateMismatch =
            confirmedHashes.size() != run.candidates().size();
        int commonSize = Math.min(
            confirmedHashes.size(),
            run.candidates().size());
        for (int index = 0; index < run.candidates().size(); index++) {
            String actual = SchematicProofPlan.requireSha256(
                run.candidates().get(index).contentHash(),
                "resolved candidate hash");
            actualHashes.add(actual);
            if (index < commonSize
                    && !actual.equals(confirmedHashes.get(index))) {
                candidateMismatch = true;
            }
        }

        if (candidateMismatch
                || !plan.contentHash().equals(run.planHash())
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
                || actualHashes.size() != confirmation.retainedSolutions()
                || confirmation.exactReplayExecutions() < 1) {
            throw new IllegalArgumentException(
                "replay confirmation differs from the supplied plan run");
        }
        SchematicProofPlan.requireSha256(
            confirmation.receiptVerificationHash(),
            "receiptVerificationHash");
        SchematicProofPlan.requireSha256(
            confirmation.receiptContentHash(),
            "receiptContentHash");
        SchematicProofPlan.requireSha256(
            confirmation.confirmationHash(),
            "confirmationHash");
        return List.copyOf(actualHashes);
    }

    /** Complete membership pass over the confirmed candidate identities. */
    private static ExactFinitePolynomialResolvedCandidate selectCandidate(
        ConfirmedReplay confirmation,
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

        // Separate complete typed-candidate selection pass.
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

    /** Complete solution-membership pass plus exact link validation. */
    private static void requireCandidateLinks(
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run,
        ExactFinitePolynomialResolvedCandidate candidate
    ) {
        SearchResult result = run.solverResult();
        long matchingSolutions = result.solutions().stream()
            .filter(candidate.solution()::equals)
            .count();
        if (!candidate.resolution().planHash().equals(plan.contentHash())
                || !candidate.solverResultHash().equals(result.contentHash())
                || !candidate.resolution().isStructurallyCompleteFor(plan)
                || matchingSolutions != 1) {
            throw new IllegalArgumentException(
                "selected candidate links differ from plan-run authority");
        }
    }

    private static void requireReceiptReference(ArtifactReference reference) {
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

    private static void requirePlanRunReference(ArtifactReference reference) {
        Objects.requireNonNull(reference, "planRunReference");
        if (!ExactFinitePolynomialPlanReplayArtifactVerifier.REFERENCE_SCHEMA
                .equals(reference.referenceSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .PLAN_RUN_ROLE.equals(reference.role())
                || !ExactFinitePolynomialPlanRun.SCHEMA.equals(
                    reference.contentSchema())
                || !ExactFinitePolynomialPlanReplayArtifactVerifier
                    .PLAN_RUN_MEDIA_TYPE.equals(reference.mediaType())) {
            throw new IllegalArgumentException(
                "confirmation plan-run reference has the wrong role or schema");
        }
    }

    private static String render(EvidenceData data, String evidenceHash) {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", data.schema())
            .property("verifierId", data.verifierId())
            .property(
                "verifierRevisionHash",
                data.verifierRevisionHash())
            .property("theoryStepId", data.theoryStepId())
            .object("receiptReference", writer ->
                writeReference(writer, data.receiptReference()))
            .object("planRunReference", writer ->
                writeReference(writer, data.planRunReference()))
            .property(
                "receiptVerificationHash",
                data.receiptVerificationHash())
            .property("confirmationHash", data.confirmationHash())
            .property("planHash", data.planHash())
            .property("planRunHash", data.planRunHash())
            .property("solverResultHash", data.solverResultHash())
            .property("solverRevisionHash", data.solverRevisionHash())
            .property("runStatus", data.runStatus().name())
            .property("totalAssignments", data.totalAssignments())
            .property("evaluatedAssignments", data.evaluatedAssignments())
            .property("matchingAssignments", data.matchingAssignments())
            .property(
                "retainedCandidateCount",
                data.retainedCandidateCount())
            .property("candidateHash", data.candidateHash())
            .property("solutionHash", data.solutionHash())
            .property("resolutionHash", data.resolutionHash())
            .property("sourceExpression", data.sourceExpression())
            .property(
                "transformedExpression",
                data.transformedExpression())
            .property("exactNormalForm", data.exactNormalForm())
            .stringArray("assumptions", data.assumptions())
            .object("canonicalWork", writer ->
                writeWork(writer, data.canonicalWork()));
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

    private static void writeWork(JsonWriter writer, CanonicalWork work) {
        writer.property(
                "exactReplayExecutions",
                work.exactReplayExecutions())
            .property(
                "evaluatedAssignmentsPerReplay",
                work.evaluatedAssignmentsPerReplay())
            .property(
                "replayAssignmentEvaluations",
                work.replayAssignmentEvaluations())
            .property(
                "retainedCandidateCount",
                work.retainedCandidateCount())
            .property(
                "planRunMaterializationCandidateVisits",
                work.planRunMaterializationCandidateVisits())
            .property(
                "confirmationEqualityCandidateVisits",
                work.confirmationEqualityCandidateVisits())
            .property(
                "confirmationMembershipCandidateVisits",
                work.confirmationMembershipCandidateVisits())
            .property(
                "typedCandidateSelectionCandidateVisits",
                work.typedCandidateSelectionCandidateVisits())
            .property(
                "solutionMembershipCandidateVisits",
                work.solutionMembershipCandidateVisits())
            .property(
                "totalCandidateIdentityVisits",
                work.totalCandidateIdentityVisits())
            .property("totalWorkUnits", work.totalWorkUnits());
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

    private static void validateEvidenceCounts(
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        int retainedCandidateCount
    ) {
        if (totalAssignments < 1
                || evaluatedAssignments != totalAssignments
                || matchingAssignments < 0
                || matchingAssignments > evaluatedAssignments
                || retainedCandidateCount < 1) {
            throw new IllegalArgumentException(
                "selected candidate evidence counts are inconsistent");
        }
        boolean validStatus = switch (runStatus) {
            case COMPLETE_WITHOUT_SOLUTION -> false;
            case COMPLETE_WITH_RESOLUTIONS ->
                matchingAssignments == retainedCandidateCount;
            case COMPLETE_RESOLUTION_SET_TRUNCATED ->
                matchingAssignments > retainedCandidateCount;
        };
        if (!validStatus) {
            throw new IllegalArgumentException(
                "selected candidate evidence status differs from counts");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Public read-only payload. Constructing this value grants no authority;
     * only the verifier-owned sealed wrapper represents positive evidence.
     */
    public record EvidenceData(
        String schema,
        String verifierId,
        String verifierRevisionHash,
        String theoryStepId,
        ArtifactReference receiptReference,
        ArtifactReference planRunReference,
        String receiptVerificationHash,
        String confirmationHash,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        int retainedCandidateCount,
        String candidateHash,
        String solutionHash,
        String resolutionHash,
        String sourceExpression,
        String transformedExpression,
        String exactNormalForm,
        List<String> assumptions,
        CanonicalWork canonicalWork
    ) {
        public EvidenceData {
            if (!EVIDENCE_SCHEMA.equals(schema)
                    || !VERIFIER_ID.equals(verifierId)
                    || !REVISION_HASH.equals(verifierRevisionHash)
                    || !THEORY_STEP_ID.equals(theoryStepId)) {
                throw new IllegalArgumentException(
                    "selected candidate evidence revision is unsupported");
            }
            requireReceiptReference(receiptReference);
            requirePlanRunReference(planRunReference);
            receiptVerificationHash = SchematicProofPlan.requireSha256(
                receiptVerificationHash,
                "receiptVerificationHash");
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
            validateEvidenceCounts(
                runStatus,
                totalAssignments,
                evaluatedAssignments,
                matchingAssignments,
                retainedCandidateCount);
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
            if (sourceExpression.equals(transformedExpression)) {
                throw new IllegalArgumentException(
                    "selected candidate evidence must change representation");
            }
            exactNormalForm = requireText(exactNormalForm, "exactNormalForm");
            assumptions = List.copyOf(
                Objects.requireNonNull(assumptions, "assumptions"));
            if (!assumptions.isEmpty()) {
                throw new IllegalArgumentException(
                    "v1 selected candidate evidence is assumption-free");
            }
            canonicalWork = Objects.requireNonNull(
                canonicalWork,
                "canonicalWork");
            if (canonicalWork.evaluatedAssignmentsPerReplay()
                    != evaluatedAssignments
                    || canonicalWork.retainedCandidateCount()
                        != retainedCandidateCount) {
                throw new IllegalArgumentException(
                    "candidate evidence work differs from retained counts");
            }
        }
    }

    /**
     * Canonical work visible at this boundary. Stored run and receipt artifacts
     * do not prove historical generation work; only the replay executions
     * reported by the confirmation are charged as assignment evaluations.
     */
    public record CanonicalWork(
        int exactReplayExecutions,
        long evaluatedAssignmentsPerReplay,
        long replayAssignmentEvaluations,
        int retainedCandidateCount,
        long planRunMaterializationCandidateVisits,
        long confirmationEqualityCandidateVisits,
        long confirmationMembershipCandidateVisits,
        long typedCandidateSelectionCandidateVisits,
        long solutionMembershipCandidateVisits,
        long totalCandidateIdentityVisits,
        long totalWorkUnits
    ) {
        public CanonicalWork {
            if (exactReplayExecutions < 1
                    || evaluatedAssignmentsPerReplay < 1
                    || retainedCandidateCount < 1) {
                throw new IllegalArgumentException(
                    "candidate evidence work inputs are inconsistent");
            }
            long expectedReplayEvaluations = Math.multiplyExact(
                (long) exactReplayExecutions,
                evaluatedAssignmentsPerReplay);
            if (replayAssignmentEvaluations != expectedReplayEvaluations) {
                throw new IllegalArgumentException(
                    "replay assignment work differs from confirmed executions");
            }
            long candidates = retainedCandidateCount;
            if (planRunMaterializationCandidateVisits != candidates
                    || confirmationEqualityCandidateVisits != candidates
                    || confirmationMembershipCandidateVisits != candidates
                    || typedCandidateSelectionCandidateVisits != candidates
                    || solutionMembershipCandidateVisits != candidates) {
                throw new IllegalArgumentException(
                    "candidate evidence requires five complete identity passes");
            }
            long expectedCandidateVisits = Math.multiplyExact(
                (long) CANDIDATE_IDENTITY_PASSES,
                candidates);
            if (totalCandidateIdentityVisits != expectedCandidateVisits) {
                throw new IllegalArgumentException(
                    "candidate identity total differs from five full passes");
            }
            long expectedTotal = Math.addExact(
                expectedReplayEvaluations,
                expectedCandidateVisits);
            if (totalWorkUnits != expectedTotal) {
                throw new IllegalArgumentException(
                    "candidate evidence total work is inconsistent");
            }
        }

        static CanonicalWork fromConfirmation(
            ConfirmedReplay confirmation,
            long evaluatedAssignments,
            int retainedCandidates
        ) {
            int replayExecutions = confirmation.exactReplayExecutions();
            long replayEvaluations = Math.multiplyExact(
                (long) replayExecutions,
                evaluatedAssignments);
            long candidateVisits = retainedCandidates;
            long totalCandidateVisits = Math.multiplyExact(
                (long) CANDIDATE_IDENTITY_PASSES,
                candidateVisits);
            return new CanonicalWork(
                replayExecutions,
                evaluatedAssignments,
                replayEvaluations,
                retainedCandidates,
                candidateVisits,
                candidateVisits,
                candidateVisits,
                candidateVisits,
                candidateVisits,
                totalCandidateVisits,
                Math.addExact(replayEvaluations, totalCandidateVisits));
        }
    }

    /**
     * Read-only evidence surface with one private permitted implementation.
     * Receiving this value confirms only the selected exact theory step.
     */
    public sealed interface VerifiedCandidateEvidence
            permits VerifiedEvidence {
        EvidenceData data();

        String evidenceHash();

        String toCanonicalJson();
    }

    private record VerifiedEvidence(
        EvidenceData data,
        String evidenceHash
    ) implements VerifiedCandidateEvidence {
        private VerifiedEvidence {
            data = Objects.requireNonNull(data, "data");
            evidenceHash = SchematicProofPlan.requireSha256(
                evidenceHash,
                "evidenceHash");
            String payload = render(data, null);
            requireSize(payload);
            if (!SchematicProofPlan.hash(payload).equals(evidenceHash)) {
                throw new IllegalArgumentException(
                    "selected candidate evidence hash differs from contents");
            }
            requireSize(render(data, evidenceHash));
        }

        @Override
        public String toCanonicalJson() {
            return render(data, evidenceHash);
        }
    }
}
