package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Repeats a complete finite polynomial plan run and issues a content-addressed
 * receipt only when the replay is byte-for-byte identical.
 *
 * <p>The receipt confirms deterministic replay under the bound resolver and
 * solver revisions. It is not formal proof evidence, an executable rewrite
 * program or promotion authority.</p>
 */
public final class ExactFinitePolynomialPlanReplayVerifier {
    public static final String VERIFIER_ID =
        "regelsuche.exact-finite-polynomial-plan-replay-verifier/v1";
    public static final String REVISION_HASH = SchematicProofPlan.hash(
        lengthPrefixed(
            VERIFIER_ID,
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            "complete-run-reexecution",
            "exact-plan-run-equality",
            "sealed-verifier-owned-non-executable-receipt"));

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();

    public ReplayReceipt verify(
        SchematicProofPlan plan,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> holeDomains,
        int retainedSolutionLimit,
        ExactFinitePolynomialPlanRun expected
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(expected, "expected");
        if (!plan.contentHash().equals(expected.planHash())) {
            throw new IllegalArgumentException(
                "expected plan run is bound to a different plan");
        }

        ExactFinitePolynomialPlanRun replayed = resolver.resolve(
            plan,
            sourceExpression,
            ansatzTemplate,
            holeDomains,
            retainedSolutionLimit);
        if (!expected.equals(replayed)) {
            throw new IllegalArgumentException(
                "finite polynomial plan replay differs from expected run");
        }
        return createReceipt(plan, replayed);
    }

    private static ReplayReceipt createReceipt(
        SchematicProofPlan plan,
        ExactFinitePolynomialPlanRun run
    ) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(run, "run");
        if (!plan.contentHash().equals(run.planHash())) {
            throw new IllegalArgumentException(
                "plan run is bound to a different schematic plan");
        }
        List<String> candidateHashes = run.candidates().stream()
            .map(ExactFinitePolynomialResolvedCandidate::contentHash)
            .sorted()
            .toList();
        var result = run.solverResult();
        String payload = VerifiedReplayReceipt.render(
            ReplayReceipt.SCHEMA,
            VERIFIER_ID,
            REVISION_HASH,
            plan.contentHash(),
            run.contentHash(),
            result.contentHash(),
            result.solverRevisionHash(),
            run.status(),
            result.totalAssignments(),
            result.evaluatedAssignments(),
            result.matchingAssignments(),
            candidateHashes.size(),
            candidateHashes,
            ReplayStatus.CONFIRMED_IDENTICAL_REPLAY,
            null);
        VerifiedReplayReceipt.requireSize(payload);
        return new VerifiedReplayReceipt(
            ReplayReceipt.SCHEMA,
            VERIFIER_ID,
            REVISION_HASH,
            plan.contentHash(),
            run.contentHash(),
            result.contentHash(),
            result.solverRevisionHash(),
            run.status(),
            result.totalAssignments(),
            result.evaluatedAssignments(),
            result.matchingAssignments(),
            candidateHashes.size(),
            candidateHashes,
            ReplayStatus.CONFIRMED_IDENTICAL_REPLAY,
            SchematicProofPlan.hash(payload));
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

    /**
     * Public read-only receipt surface with a single private permitted
     * implementation. External code cannot construct another implementation
     * that asserts a confirmed replay.
     */
    public sealed interface ReplayReceipt permits VerifiedReplayReceipt {
        String SCHEMA =
            "regelsuche.exact-finite-polynomial-plan-replay-receipt/v1";

        String schema();

        String verifierId();

        String verifierRevisionHash();

        String planHash();

        String planRunHash();

        String solverResultHash();

        String solverRevisionHash();

        ExactFinitePolynomialPlanRun.Status runStatus();

        long totalAssignments();

        long evaluatedAssignments();

        long matchingAssignments();

        int retainedSolutions();

        List<String> resolvedCandidateHashes();

        ReplayStatus replayStatus();

        String contentHash();

        boolean matches(
            SchematicProofPlan plan,
            ExactFinitePolynomialPlanRun run);

        String toCanonicalJson();
    }

    private record VerifiedReplayReceipt(
        String schema,
        String verifierId,
        String verifierRevisionHash,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevisionHash,
        ExactFinitePolynomialPlanRun.Status runStatus,
        long totalAssignments,
        long evaluatedAssignments,
        long matchingAssignments,
        int retainedSolutions,
        List<String> resolvedCandidateHashes,
        ReplayStatus replayStatus,
        String contentHash
    ) implements ReplayReceipt {
        private static final int MAX_RECEIPT_BYTES = 1_000_000;

        private VerifiedReplayReceipt {
            if (!ReplayReceipt.SCHEMA.equals(schema)) {
                throw new IllegalArgumentException(
                    "unsupported finite polynomial replay receipt schema");
            }
            if (!VERIFIER_ID.equals(verifierId)) {
                throw new IllegalArgumentException(
                    "unexpected finite polynomial replay verifier ID");
            }
            if (!REVISION_HASH.equals(verifierRevisionHash)) {
                throw new IllegalArgumentException(
                    "unexpected finite polynomial replay verifier revision");
            }
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
            if (!ExactFinitePolynomialHoleSolver.REVISION_HASH.equals(
                    solverRevisionHash)) {
                throw new IllegalArgumentException(
                    "receipt requires the current exact finite solver revision");
            }
            runStatus = Objects.requireNonNull(runStatus, "runStatus");
            replayStatus = Objects.requireNonNull(
                replayStatus,
                "replayStatus");
            if (replayStatus != ReplayStatus.CONFIRMED_IDENTICAL_REPLAY) {
                throw new IllegalArgumentException(
                    "v1 receipts require an identical confirmed replay");
            }
            if (totalAssignments < 1
                    || evaluatedAssignments != totalAssignments
                    || matchingAssignments < 0
                    || matchingAssignments > evaluatedAssignments
                    || retainedSolutions < 0
                    || retainedSolutions > matchingAssignments) {
                throw new IllegalArgumentException(
                    "replay assignment counts are inconsistent");
            }
            resolvedCandidateHashes = normalizeHashes(
                resolvedCandidateHashes);
            if (resolvedCandidateHashes.size() != retainedSolutions) {
                throw new IllegalArgumentException(
                    "resolved candidate count differs from retained solutions");
            }
            validateStatusCounts(
                runStatus,
                matchingAssignments,
                retainedSolutions);
            contentHash = SchematicProofPlan.requireSha256(
                contentHash,
                "contentHash");
            String payload = render(
                schema,
                verifierId,
                verifierRevisionHash,
                planHash,
                planRunHash,
                solverResultHash,
                solverRevisionHash,
                runStatus,
                totalAssignments,
                evaluatedAssignments,
                matchingAssignments,
                retainedSolutions,
                resolvedCandidateHashes,
                replayStatus,
                null);
            requireSize(payload);
            if (!SchematicProofPlan.hash(payload).equals(contentHash)) {
                throw new IllegalArgumentException(
                    "replay receipt contentHash does not match contents");
            }
            requireSize(render(
                schema,
                verifierId,
                verifierRevisionHash,
                planHash,
                planRunHash,
                solverResultHash,
                solverRevisionHash,
                runStatus,
                totalAssignments,
                evaluatedAssignments,
                matchingAssignments,
                retainedSolutions,
                resolvedCandidateHashes,
                replayStatus,
                contentHash));
        }

        @Override
        public boolean matches(
            SchematicProofPlan plan,
            ExactFinitePolynomialPlanRun run
        ) {
            try {
                return equals(createReceipt(plan, run));
            } catch (IllegalArgumentException rejected) {
                return false;
            }
        }

        @Override
        public String toCanonicalJson() {
            return render(
                schema,
                verifierId,
                verifierRevisionHash,
                planHash,
                planRunHash,
                solverResultHash,
                solverRevisionHash,
                runStatus,
                totalAssignments,
                evaluatedAssignments,
                matchingAssignments,
                retainedSolutions,
                resolvedCandidateHashes,
                replayStatus,
                contentHash);
        }

        private static List<String> normalizeHashes(List<String> values) {
            Objects.requireNonNull(values, "resolvedCandidateHashes");
            List<String> result = values.stream()
                .map(value -> SchematicProofPlan.requireSha256(
                    value,
                    "resolved candidate hash"))
                .sorted(Comparator.naturalOrder())
                .toList();
            if (new HashSet<>(result).size() != result.size()) {
                throw new IllegalArgumentException(
                    "resolved candidate hashes must be unique");
            }
            return List.copyOf(result);
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
                    "replay status differs from assignment counts");
            }
        }

        private static String render(
            String schema,
            String verifierId,
            String verifierRevisionHash,
            String planHash,
            String planRunHash,
            String solverResultHash,
            String solverRevisionHash,
            ExactFinitePolynomialPlanRun.Status runStatus,
            long totalAssignments,
            long evaluatedAssignments,
            long matchingAssignments,
            int retainedSolutions,
            List<String> candidateHashes,
            ReplayStatus replayStatus,
            String contentHash
        ) {
            JsonWriter json = new JsonWriter().beginObject()
                .property("schema", schema)
                .property("verifierId", verifierId)
                .property("verifierRevisionHash", verifierRevisionHash)
                .property("planHash", planHash)
                .property("planRunHash", planRunHash)
                .property("solverResultHash", solverResultHash)
                .property("solverRevisionHash", solverRevisionHash)
                .property("runStatus", runStatus.name())
                .property("totalAssignments", totalAssignments)
                .property("evaluatedAssignments", evaluatedAssignments)
                .property("matchingAssignments", matchingAssignments)
                .property("retainedSolutions", retainedSolutions)
                .stringArray(
                    "resolvedCandidateHashes",
                    candidateHashes)
                .property("replayStatus", replayStatus.name());
            if (contentHash != null) {
                json.property("contentHash", contentHash);
            }
            return json.endObject().toString();
        }

        private static void requireSize(String value) {
            if (value.getBytes(StandardCharsets.UTF_8).length
                    > MAX_RECEIPT_BYTES) {
                throw new IllegalArgumentException(
                    "finite polynomial replay receipt exceeds byte limit");
            }
        }
    }

    public enum ReplayStatus {
        CONFIRMED_IDENTICAL_REPLAY
    }
}
