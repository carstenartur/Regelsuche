package de.regelsuche.evolution;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchStatus;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Content-addressed aggregate of one finite solver run and its plan resolutions. */
public record ExactFinitePolynomialPlanRun(
    String resolverId,
    String resolverRevisionHash,
    String planHash,
    SearchResult solverResult,
    Status status,
    List<ExactFinitePolynomialResolvedCandidate> candidates,
    String contentHash
) {
    public static final String SCHEMA =
        "regelsuche.exact-finite-polynomial-plan-run/v1";
    public static final int MAX_CANONICAL_BYTES = 1_000_000;
    public static final String ARTIFACT_REVISION_HASH =
        SchematicProofPlan.hash(lengthPrefixed(
            SCHEMA,
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            ExactFinitePolynomialHoleSolver.SOLUTION_IDENTITY_REVISION,
            SchematicProofPlanResolution.SCHEMA,
            Integer.toString(MAX_CANONICAL_BYTES),
            "ordered-complete-run-projection",
            "typed-domains-solutions-and-resolution-links",
            "plan-run-semantic-hash-plus-independent-byte-address"));

    public ExactFinitePolynomialPlanRun {
        if (!ExactFinitePolynomialPlanResolver.RESOLVER_ID.equals(resolverId)) {
            throw new IllegalArgumentException(
                "unexpected finite plan resolver ID");
        }
        if (!ExactFinitePolynomialPlanResolver.REVISION_HASH.equals(
                resolverRevisionHash)) {
            throw new IllegalArgumentException(
                "unexpected finite plan resolver revision");
        }
        planHash = SchematicProofPlan.requireSha256(planHash, "planHash");
        solverResult = Objects.requireNonNull(solverResult, "solverResult");
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(candidates, "candidates");
        candidates = candidates.stream()
            .map(candidate -> Objects.requireNonNull(candidate, "candidate"))
            .sorted(Comparator.comparing(
                ExactFinitePolynomialResolvedCandidate::contentHash))
            .toList();
        if (candidates.stream()
                .map(ExactFinitePolynomialResolvedCandidate::contentHash)
                .distinct().count() != candidates.size()) {
            throw new IllegalArgumentException(
                "resolved candidates must be unique");
        }
        validateStatus(solverResult.status(), status, candidates.size());
        validateCandidateLinks(planHash, solverResult, candidates);
        contentHash = SchematicProofPlan.requireSha256(
            contentHash,
            "contentHash");
        String expected = ExactFinitePolynomialPlanResolver.planRunHash(
            planHash,
            solverResult,
            status,
            candidates);
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "plan-run contentHash does not match contents");
        }
    }

    static ExactFinitePolynomialPlanRun create(
        String planHash,
        SearchResult solverResult,
        List<ExactFinitePolynomialResolvedCandidate> candidates
    ) {
        Status status = switch (solverResult.status()) {
            case COMPLETE_WITHOUT_SOLUTION ->
                Status.COMPLETE_WITHOUT_SOLUTION;
            case COMPLETE_WITH_SOLUTIONS ->
                Status.COMPLETE_WITH_RESOLUTIONS;
            case COMPLETE_SOLUTION_SET_TRUNCATED ->
                Status.COMPLETE_RESOLUTION_SET_TRUNCATED;
        };
        return new ExactFinitePolynomialPlanRun(
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            planHash,
            solverResult,
            status,
            candidates,
            ExactFinitePolynomialPlanResolver.planRunHash(
                planHash,
                solverResult,
                status,
                candidates));
    }

    /**
     * Canonical retained-byte projection of this already validated plan run.
     *
     * <p>The semantic run hash and the independent artifact byte hash remain
     * distinct identities. Parsing or replay authority is not granted by this
     * representation alone.</p>
     */
    public String toCanonicalJson() {
        JsonWriter json = new JsonWriter().beginObject()
            .property("schema", SCHEMA)
            .property("artifactRevisionHash", ARTIFACT_REVISION_HASH)
            .property("resolverId", resolverId)
            .property("resolverRevisionHash", resolverRevisionHash)
            .property("planHash", planHash)
            .property("planRunHash", contentHash)
            .property("solverId", solverResult.solverId())
            .property(
                "solverRevisionHash",
                solverResult.solverRevisionHash())
            .property("solverResultHash", solverResult.contentHash())
            .property(
                "sourceExpression",
                solverResult.sourceExpression())
            .property("ansatzTemplate", solverResult.ansatzTemplate())
            .array("holeDomains", array ->
                solverResult.holeDomains().forEach(domain ->
                    array.objectValue(object ->
                        writeDomain(object, domain))))
            .property(
                "totalAssignments",
                solverResult.totalAssignments())
            .property(
                "evaluatedAssignments",
                solverResult.evaluatedAssignments())
            .property(
                "matchingAssignments",
                solverResult.matchingAssignments())
            .property(
                "retainedSolutionLimit",
                solverResult.retainedSolutionLimit())
            .property("solverStatus", solverResult.status().name())
            .property("runStatus", status.name())
            .array("solutions", array ->
                solverResult.solutions().forEach(solution ->
                    array.objectValue(object ->
                        writeSolution(object, solution))))
            .array("resolvedCandidates", array ->
                candidates.forEach(candidate ->
                    array.objectValue(object ->
                        writeCandidate(object, candidate))));
        String result = json.endObject().toString();
        requireCanonicalSize(result);
        return result;
    }

    private static void writeDomain(
        JsonWriter json,
        HoleDomain domain
    ) {
        json.property("holeId", domain.holeId())
            .property("kind", domain.kind().name())
            .stringArray(
                "values",
                domain.values().stream()
                    .map(value -> value.canonicalText())
                    .toList());
    }

    private static void writeSolution(
        JsonWriter json,
        Solution solution
    ) {
        json.property("solutionHash", solution.contentHash())
            .array("bindings", array ->
                solution.bindings().forEach(binding ->
                    array.objectValue(object -> object
                        .property("holeId", binding.holeId())
                        .property("kind", binding.kind().name())
                        .property(
                            "value",
                            binding.value().canonicalText()))))
            .property(
                "instantiatedExpression",
                solution.instantiatedExpression())
            .property("exactNormalForm", solution.exactNormalForm());
    }

    private static void writeCandidate(
        JsonWriter json,
        ExactFinitePolynomialResolvedCandidate candidate
    ) {
        json.property("candidateHash", candidate.contentHash())
            .property("solutionHash", candidate.solution().contentHash())
            .property("solverResultHash", candidate.solverResultHash())
            .property(
                "resolutionHash",
                candidate.resolution().contentHash())
            .object("resolution", object ->
                writeResolution(object, candidate.resolution()));
    }

    private static void writeResolution(
        JsonWriter json,
        SchematicProofPlanResolution resolution
    ) {
        json.property("schema", resolution.schema())
            .property("planHash", resolution.planHash())
            .stringArray(
                "requiredHoleIds",
                resolution.requiredHoleIds())
            .stringArray(
                "requiredObligationIds",
                resolution.requiredObligationIds())
            .array("bindings", array ->
                resolution.bindings().forEach(binding ->
                    array.objectValue(object -> object
                        .property("holeId", binding.holeId())
                        .property("sort", binding.sort().name())
                        .property(
                            "canonicalValue",
                            binding.canonicalValue())
                        .property(
                            "evidenceHash",
                            binding.evidenceHash()))))
            .array("outcomes", array ->
                resolution.outcomes().forEach(outcome ->
                    array.objectValue(object -> object
                        .property(
                            "obligationId",
                            outcome.obligationId())
                        .property("status", outcome.status().name())
                        .property(
                            "checkerCapability",
                            outcome.checkerCapability())
                        .property(
                            "checkerRevisionHash",
                            outcome.checkerRevisionHash())
                        .property(
                            "checkerExecutionHash",
                            outcome.checkerExecutionHash())
                        .property(
                            "detailCode",
                            outcome.detailCode()))))
            .property("state", resolution.state().name())
            .property("contentHash", resolution.contentHash());
    }

    private static void requireCanonicalSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length
                > MAX_CANONICAL_BYTES) {
            throw new IllegalArgumentException(
                "finite polynomial plan-run artifact exceeds byte limit");
        }
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

    private static void validateCandidateLinks(
        String planHash,
        SearchResult solverResult,
        List<ExactFinitePolynomialResolvedCandidate> candidates
    ) {
        String solverHash = solverResult.contentHash();
        for (ExactFinitePolynomialResolvedCandidate candidate : candidates) {
            if (!planHash.equals(candidate.resolution().planHash())
                    || !solverHash.equals(candidate.solverResultHash())) {
                throw new IllegalArgumentException(
                    "resolved candidate differs from plan-run authority");
            }
        }
        List<Solution> expectedSolutions = solverResult.solutions().stream()
            .sorted(Comparator.comparing(Solution::bindingKey))
            .toList();
        List<Solution> actualSolutions = candidates.stream()
            .map(ExactFinitePolynomialResolvedCandidate::solution)
            .sorted(Comparator.comparing(Solution::bindingKey))
            .toList();
        if (!expectedSolutions.equals(actualSolutions)) {
            throw new IllegalArgumentException(
                "resolved candidates do not cover solver solutions exactly");
        }
    }

    private static void validateStatus(
        SearchStatus solverStatus,
        Status planStatus,
        int candidateCount
    ) {
        Status expected = switch (solverStatus) {
            case COMPLETE_WITHOUT_SOLUTION ->
                Status.COMPLETE_WITHOUT_SOLUTION;
            case COMPLETE_WITH_SOLUTIONS ->
                Status.COMPLETE_WITH_RESOLUTIONS;
            case COMPLETE_SOLUTION_SET_TRUNCATED ->
                Status.COMPLETE_RESOLUTION_SET_TRUNCATED;
        };
        boolean candidateCountMatches =
            solverStatus == SearchStatus.COMPLETE_WITHOUT_SOLUTION
                ? candidateCount == 0
                : candidateCount > 0;
        if (planStatus != expected || !candidateCountMatches) {
            throw new IllegalArgumentException(
                "plan status differs from exact solver result");
        }
    }

    public enum Status {
        COMPLETE_WITHOUT_SOLUTION,
        COMPLETE_WITH_RESOLUTIONS,
        COMPLETE_RESOLUTION_SET_TRUNCATED
    }
}
