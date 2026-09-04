package de.regelsuche.evolution;

import de.regelsuche.evolution.SchematicProofPlan.HoleSort;
import de.regelsuche.evolution.SchematicProofPlanResolution.HoleBinding;
import de.regelsuche.evolution.SchematicProofPlanResolution.ObligationOutcome;
import de.regelsuche.evolution.SchematicProofPlanResolution.OutcomeStatus;
import de.regelsuche.evolution.SchematicProofPlanResolution.ResolutionState;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Binding;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One exact finite solver solution bound to one complete plan resolution. */
public record ExactFinitePolynomialResolvedCandidate(
    Solution solution,
    SchematicProofPlanResolution resolution,
    String solverResultHash,
    String contentHash
) {
    public ExactFinitePolynomialResolvedCandidate {
        solution = Objects.requireNonNull(solution, "solution");
        resolution = Objects.requireNonNull(resolution, "resolution");
        solverResultHash = SchematicProofPlan.requireSha256(
            solverResultHash,
            "solverResultHash");
        contentHash = SchematicProofPlan.requireSha256(
            contentHash,
            "contentHash");
        if (resolution.state() != ResolutionState.COMPLETE_REFERENCES) {
            throw new IllegalArgumentException(
                "resolved candidate requires complete references");
        }
        validateBindings(solution, resolution, solverResultHash);
        validateOutcome(resolution, solverResultHash);
        String expected = ExactFinitePolynomialPlanResolver.candidateHash(
            resolution.planHash(),
            solverResultHash,
            solution.contentHash(),
            resolution.contentHash());
        if (!expected.equals(contentHash)) {
            throw new IllegalArgumentException(
                "resolved candidate contentHash does not match contents");
        }
    }

    static ExactFinitePolynomialResolvedCandidate create(
        Solution solution,
        SchematicProofPlanResolution resolution,
        String solverResultHash
    ) {
        return new ExactFinitePolynomialResolvedCandidate(
            solution,
            resolution,
            solverResultHash,
            ExactFinitePolynomialPlanResolver.candidateHash(
                resolution.planHash(),
                solverResultHash,
                solution.contentHash(),
                resolution.contentHash()));
    }

    private static void validateBindings(
        Solution solution,
        SchematicProofPlanResolution resolution,
        String solverResultHash
    ) {
        Map<String, Binding> expected = new LinkedHashMap<>();
        for (Binding binding : solution.bindings()) {
            expected.put(binding.holeId(), binding);
        }
        if (expected.size() != resolution.bindings().size()) {
            throw new IllegalArgumentException(
                "plan resolution bindings differ from solver solution");
        }
        for (HoleBinding actual : resolution.bindings()) {
            Binding binding = expected.get(actual.holeId());
            HoleSort expectedSort = binding == null
                ? null
                : binding.kind()
                    == ExactFinitePolynomialHoleSolver.HoleKind.SIGN
                        ? HoleSort.SIGN
                        : HoleSort.EXACT_RATIONAL;
            if (binding == null
                    || actual.sort() != expectedSort
                    || !binding.value().canonicalText().equals(
                        actual.canonicalValue())
                    || !ExactFinitePolynomialPlanResolver.bindingEvidenceHash(
                        resolution.planHash(),
                        solverResultHash,
                        solution.contentHash(),
                        actual.holeId(),
                        actual.canonicalValue()).equals(
                            actual.evidenceHash())) {
                throw new IllegalArgumentException(
                    "plan resolution bindings differ from solver solution");
            }
        }
    }

    private static void validateOutcome(
        SchematicProofPlanResolution resolution,
        String solverResultHash
    ) {
        if (resolution.outcomes().size() != 1) {
            throw new IllegalArgumentException(
                "resolved candidate requires one equivalence outcome");
        }
        ObligationOutcome outcome = resolution.outcomes().getFirst();
        if (!ExactFinitePolynomialPlanResolver.EQUIVALENCE_OBLIGATION_ID.equals(
                outcome.obligationId())
                || outcome.status() != OutcomeStatus.CONFIRMED
                || !ExactFinitePolynomialHoleSolver.SOLVER_ID.equals(
                    outcome.checkerCapability())
                || !ExactFinitePolynomialHoleSolver.REVISION_HASH.equals(
                    outcome.checkerRevisionHash())
                || !solverResultHash.equals(outcome.checkerExecutionHash())
                || !"EXACT_FINITE_POLYNOMIAL_EQUIVALENCE_CONFIRMED".equals(
                    outcome.detailCode())) {
            throw new IllegalArgumentException(
                "resolved candidate outcome differs from solver execution");
        }
    }
}
