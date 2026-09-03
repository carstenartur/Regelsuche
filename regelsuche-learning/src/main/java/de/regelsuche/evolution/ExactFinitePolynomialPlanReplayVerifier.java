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
            "verifier-owned-non-executable-receipt"));

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
        return ReplayReceipt.create(plan, replayed);
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
     * Immutable replay receipt whose construction is owned by the verifier.
     *
     * <p>There is intentionally no public or package-visible constructor and
     * no parse/factory API that can assert replay without running
     * {@link ExactFinitePolynomialPlanReplayVerifier#verify}.</p>
     */
    public static final class ReplayReceipt {
        public static final String SCHEMA =
            "regelsuche.exact-finite-polynomial-plan-replay-receipt/v1";
        private static final int MAX_RECEIPT_BYTES = 1_000_000;

        private final String schema;
        private final String verifierId;
        private final String verifierRevisionHash;
        private final String planHash;
        private final String planRunHash;
        private final String solverResultHash;
        private final String solverRevisionHash;
        private final ExactFinitePolynomialPlanRun.Status runStatus;
        private final long totalAssignments;
        private final long evaluatedAssignments;
        private final long matchingAssignments;
        private final int retainedSolutions;
        private final List<String> resolvedCandidateHashes;
        private final ReplayStatus replayStatus;
        private final String contentHash;

        private ReplayReceipt(
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
            ReplayStatus replayStatus
        ) {
            this.schema = SCHEMA;
            if (!VERIFIER_ID.equals(verifierId)) {
                throw new IllegalArgumentException(
                    "unexpected finite polynomial replay verifier ID");
            }
            this.verifierId = verifierId;
            if (!REVISION_HASH.equals(verifierRevisionHash)) {
                throw new IllegalArgumentException(
                    "unexpected finite polynomial replay verifier revision");
            }
            this.verifierRevisionHash = verifierRevisionHash;
            this.planHash = SchematicProofPlan.requireSha256(
                planHash,
                "planHash");
            this.planRunHash = SchematicProofPlan.requireSha256(
                planRunHash,
                "planRunHash");
            this.solverResultHash = SchematicProofPlan.requireSha256(
                solverResultHash,
                "solverResultHash");
            this.solverRevisionHash = SchematicProofPlan.requireSha256(
                solverRevisionHash,
                "solverRevisionHash");
            if (!ExactFinitePolynomialHoleSolver.REVISION_HASH.equals(
                    this.solverRevisionHash)) {
                throw new IllegalArgumentException(
                    "receipt requires the current exact finite solver revision");
            }
            this.runStatus = Objects.requireNonNull(runStatus, "runStatus");
            this.replayStatus = Objects.requireNonNull(
                replayStatus,
                "replayStatus");
            if (this.replayStatus
                    != ReplayStatus.CONFIRMED_IDENTICAL_REPLAY) {
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
            this.totalAssignments = totalAssignments;
            this.evaluatedAssignments = evaluatedAssignments;
            this.matchingAssignments = matchingAssignments;
            this.retainedSolutions = retainedSolutions;
            this.resolvedCandidateHashes = normalizeHashes(
                resolvedCandidateHashes);
            if (this.resolvedCandidateHashes.size()
                    != retainedSolutions) {
                throw new IllegalArgumentException(
                    "resolved candidate count differs from retained solutions");
            }
            validateStatusCounts(
                this.runStatus,
                matchingAssignments,
                retainedSolutions);
            String payload = render(
                this.verifierId,
                this.verifierRevisionHash,
                this.planHash,
                this.planRunHash,
                this.solverResultHash,
                this.solverRevisionHash,
                this.runStatus,
                this.totalAssignments,
                this.evaluatedAssignments,
                this.matchingAssignments,
                this.retainedSolutions,
                this.resolvedCandidateHashes,
                this.replayStatus,
                null);
            requireSize(payload);
            this.contentHash = SchematicProofPlan.hash(payload);
            requireSize(toCanonicalJson());
        }

        private static ReplayReceipt create(
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
            return new ReplayReceipt(
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
                ReplayStatus.CONFIRMED_IDENTICAL_REPLAY);
        }

        public String schema() {
            return schema;
        }

        public String verifierId() {
            return verifierId;
        }

        public String verifierRevisionHash() {
            return verifierRevisionHash;
        }

        public String planHash() {
            return planHash;
        }

        public String planRunHash() {
            return planRunHash;
        }

        public String solverResultHash() {
            return solverResultHash;
        }

        public String solverRevisionHash() {
            return solverRevisionHash;
        }

        public ExactFinitePolynomialPlanRun.Status runStatus() {
            return runStatus;
        }

        public long totalAssignments() {
            return totalAssignments;
        }

        public long evaluatedAssignments() {
            return evaluatedAssignments;
        }

        public long matchingAssignments() {
            return matchingAssignments;
        }

        public int retainedSolutions() {
            return retainedSolutions;
        }

        public List<String> resolvedCandidateHashes() {
            return resolvedCandidateHashes;
        }

        public ReplayStatus replayStatus() {
            return replayStatus;
        }

        public String contentHash() {
            return contentHash;
        }

        public boolean matches(
            SchematicProofPlan plan,
            ExactFinitePolynomialPlanRun run
        ) {
            try {
                return equals(create(plan, run));
            } catch (IllegalArgumentException rejected) {
                return false;
            }
        }

        public String toCanonicalJson() {
            return render(
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

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ReplayReceipt receipt)) {
                return false;
            }
            return schema.equals(receipt.schema)
                && verifierId.equals(receipt.verifierId)
                && verifierRevisionHash.equals(receipt.verifierRevisionHash)
                && planHash.equals(receipt.planHash)
                && planRunHash.equals(receipt.planRunHash)
                && solverResultHash.equals(receipt.solverResultHash)
                && solverRevisionHash.equals(receipt.solverRevisionHash)
                && runStatus == receipt.runStatus
                && totalAssignments == receipt.totalAssignments
                && evaluatedAssignments == receipt.evaluatedAssignments
                && matchingAssignments == receipt.matchingAssignments
                && retainedSolutions == receipt.retainedSolutions
                && resolvedCandidateHashes.equals(
                    receipt.resolvedCandidateHashes)
                && replayStatus == receipt.replayStatus
                && contentHash.equals(receipt.contentHash);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
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
                .property("schema", SCHEMA)
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
