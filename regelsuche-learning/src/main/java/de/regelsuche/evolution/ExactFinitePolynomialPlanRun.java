package de.regelsuche.evolution;

import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchStatus;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
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
