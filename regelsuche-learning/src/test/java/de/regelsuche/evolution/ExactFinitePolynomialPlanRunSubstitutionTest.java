package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.SchematicProofPlan.HoleSort;
import de.regelsuche.evolution.SchematicProofPlanResolution.HoleBinding;
import de.regelsuche.evolution.SchematicProofPlanResolution.ObligationOutcome;
import de.regelsuche.evolution.SchematicProofPlanResolution.OutcomeStatus;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Binding;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleKind;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
import de.regelsuche.scalar.ExactRational;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialPlanRunSubstitutionTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();

    @Test
    void rejectsAPlanCandidateWhoseHoleKindDiffersFromTheSolverSolution() {
        String source = "x + 1";
        String ansatz = "x + ${unit}";
        SchematicProofPlan signPlan = resolver.createPlan(
            "sign-solution-plan",
            source,
            ansatz,
            List.of(HoleDomain.signs("unit")),
            8,
            LIMITS);
        ExactFinitePolynomialPlanRun signRun = resolver.resolve(
            signPlan,
            source,
            ansatz,
            List.of(HoleDomain.signs("unit")),
            8);
        Solution signSolution = signRun.solverResult().solutions().getFirst();

        Solution forgedCoefficientSolution = new Solution(
            List.of(new Binding(
                "unit",
                HoleKind.COEFFICIENT,
                ExactRational.ONE)),
            signSolution.instantiatedExpression(),
            signSolution.exactNormalForm());
        assertNotEquals(
            signSolution.contentHash(),
            forgedCoefficientSolution.contentHash(),
            "the solution identity must encode the hole kind");

        HoleDomain coefficientDomain = new HoleDomain(
            "unit",
            HoleKind.COEFFICIENT,
            List.of(ExactRational.ONE));
        SchematicProofPlan coefficientPlan = resolver.createPlan(
            "coefficient-substitution-plan",
            source,
            ansatz,
            List.of(coefficientDomain),
            8,
            LIMITS);
        String solverHash = signRun.solverResult().contentHash();
        HoleBinding forgedBinding = new HoleBinding(
            "unit",
            HoleSort.EXACT_RATIONAL,
            "1",
            ExactFinitePolynomialPlanResolver.bindingEvidenceHash(
                coefficientPlan.contentHash(),
                solverHash,
                forgedCoefficientSolution.contentHash(),
                "unit",
                "1"));
        SchematicProofPlan.Obligation obligation =
            coefficientPlan.obligations().getFirst();
        ObligationOutcome outcome = new ObligationOutcome(
            obligation.id(),
            OutcomeStatus.CONFIRMED,
            obligation.checkerCapability(),
            obligation.checkerRevisionHash(),
            solverHash,
            "EXACT_FINITE_POLYNOMIAL_EQUIVALENCE_CONFIRMED");
        SchematicProofPlanResolution forgedResolution =
            SchematicProofPlanResolution.create(
                coefficientPlan,
                List.of(forgedBinding),
                List.of(outcome));
        ExactFinitePolynomialResolvedCandidate forgedCandidate =
            ExactFinitePolynomialResolvedCandidate.create(
                forgedCoefficientSolution,
                forgedResolution,
                solverHash);
        List<ExactFinitePolynomialResolvedCandidate> candidates =
            List.of(forgedCandidate);
        ExactFinitePolynomialPlanRun.Status status =
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITH_RESOLUTIONS;

        assertThrows(IllegalArgumentException.class, () ->
            new ExactFinitePolynomialPlanRun(
                ExactFinitePolynomialPlanResolver.RESOLVER_ID,
                ExactFinitePolynomialPlanResolver.REVISION_HASH,
                coefficientPlan.contentHash(),
                signRun.solverResult(),
                status,
                candidates,
                ExactFinitePolynomialPlanResolver.planRunHash(
                    coefficientPlan.contentHash(),
                    signRun.solverResult(),
                    status,
                    candidates)));
    }
}
