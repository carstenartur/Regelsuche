package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.SchematicProofPlan.InformationBoundary;
import de.regelsuche.evolution.SchematicProofPlan.Obligation;
import de.regelsuche.evolution.SchematicProofPlan.ObligationKind;
import de.regelsuche.evolution.SchematicProofPlanResolution.HoleBinding;
import de.regelsuche.evolution.SchematicProofPlanResolution.OutcomeStatus;
import de.regelsuche.evolution.SchematicProofPlanResolution.ResolutionState;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialPlanResolverTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();

    @Test
    void resolvesQuadraticCompletionIntoACompleteReferencedPlan() {
        String source = "x^2 + 6*x + 5";
        String ansatz = "(x + ${shift})^2 + ${constant}";
        List<HoleDomain> domains = quadraticDomains();
        SchematicProofPlan plan = resolver.createPlan(
            "quadratic-completion-plan",
            source,
            ansatz,
            domains,
            8,
            LIMITS);

        assertEquals(
            InformationBoundary.TARGET_FREE_FORMATION,
            plan.informationBoundary());
        assertEquals(
            resolver.formationScopeHash(
                "quadratic-completion-plan",
                source,
                ansatz,
                domains,
                8,
                LIMITS),
            plan.formationScopeHash());
        assertEquals(List.of("constant", "shift"), plan.holeIds());
        Obligation obligation = plan.obligations().getFirst();
        assertEquals(
            ExactFinitePolynomialPlanResolver.EQUIVALENCE_OBLIGATION_ID,
            obligation.id());
        assertEquals(ObligationKind.EQUIVALENT, obligation.kind());
        assertEquals(
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            obligation.checkerCapability());
        assertEquals(
            ExactFinitePolynomialHoleSolver.REVISION_HASH,
            obligation.checkerRevisionHash());
        assertFalse(plan.toCanonicalJson().contains("targetExpression"));
        assertFalse(plan.toCanonicalJson().contains("historical"));
        assertFalse(plan.toCanonicalJson().contains("finalTest"));
        SchematicProofPlan reorderedPlan = resolver.createPlan(
            "quadratic-completion-plan",
            source,
            ansatz,
            List.of(domains.get(1), domains.get(0)),
            8,
            LIMITS);
        assertEquals(plan.contentHash(), reorderedPlan.contentHash());

        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            8);

        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITH_RESOLUTIONS,
            run.status());
        assertEquals(
            ExactFinitePolynomialPlanResolver.REVISION_HASH,
            run.resolverRevisionHash());
        assertEquals(231, run.solverResult().evaluatedAssignments());
        assertEquals(1, run.candidates().size());
        ExactFinitePolynomialResolvedCandidate candidate =
            run.candidates().getFirst();
        assertEquals(
            "constant=-4|shift=3",
            candidate.solution().bindingKey());
        assertEquals(
            ResolutionState.COMPLETE_REFERENCES,
            candidate.resolution().state());
        assertTrue(candidate.resolution().isStructurallyCompleteFor(plan));
        assertEquals(
            run.solverResult().contentHash(),
            candidate.solverResultHash());
        assertEquals(
            run.solverResult().contentHash(),
            candidate.resolution().outcomes().getFirst()
                .checkerExecutionHash());
        assertEquals(
            OutcomeStatus.CONFIRMED,
            candidate.resolution().outcomes().getFirst().status());
        assertTrue(resolver.replay(
            plan,
            source,
            ansatz,
            domains,
            8,
            run));
    }

    @Test
    void resolvesQuarticSquareDifferenceAndPreservesOutputTruncation() {
        String source = "x^4 + 4*y^4";
        String ansatz =
            "(x^2 + ${alpha}*y^2)^2 - (${beta}*x*y)^2";
        List<HoleDomain> domains = List.of(
            HoleDomain.integerRange("beta", -3, 3),
            HoleDomain.integerRange("alpha", -3, 3));
        SchematicProofPlan fullPlan = resolver.createPlan(
            "quartic-square-difference-plan",
            source,
            ansatz,
            domains,
            8,
            LIMITS);
        ExactFinitePolynomialPlanRun full = resolver.resolve(
            fullPlan,
            source,
            ansatz,
            domains,
            8);

        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITH_RESOLUTIONS,
            full.status());
        assertEquals(List.of(
            "alpha=2|beta=-2",
            "alpha=2|beta=2"),
            full.candidates().stream()
                .map(candidate -> candidate.solution().bindingKey())
                .sorted()
                .toList());
        assertTrue(resolver.replay(
            fullPlan,
            source,
            ansatz,
            domains,
            8,
            full));

        SchematicProofPlan limitedPlan = resolver.createPlan(
            "quartic-square-limited-plan",
            source,
            ansatz,
            domains,
            1,
            LIMITS);
        ExactFinitePolynomialPlanRun limited = resolver.resolve(
            limitedPlan,
            source,
            ansatz,
            domains,
            1);
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_RESOLUTION_SET_TRUNCATED,
            limited.status());
        assertEquals(2, limited.solverResult().matchingAssignments());
        assertEquals(1, limited.candidates().size());
        assertTrue(resolver.replay(
            limitedPlan,
            source,
            ansatz,
            domains,
            1,
            limited));
    }

    @Test
    void retainsACompleteNullResultWithoutInventingAResolution() {
        String source = "x^2 + 1";
        String ansatz = "(x + ${shift})^2";
        List<HoleDomain> domains = List.of(
            HoleDomain.integerRange("shift", -2, 2));
        SchematicProofPlan plan = resolver.createPlan(
            "complete-null-plan",
            source,
            ansatz,
            domains,
            4,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            4);

        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION,
            run.status());
        assertEquals(5, run.solverResult().evaluatedAssignments());
        assertEquals(0, run.solverResult().matchingAssignments());
        assertTrue(run.candidates().isEmpty());
        assertTrue(resolver.replay(
            plan,
            source,
            ansatz,
            domains,
            4,
            run));
    }

    @Test
    void rejectsScopePlanDomainAndCheckerSubstitution() {
        String source = "x^2 + 6*x + 5";
        String ansatz = "(x + ${shift})^2 + ${constant}";
        List<HoleDomain> domains = quadraticDomains();
        SchematicProofPlan plan = resolver.createPlan(
            "substitution-control-plan",
            source,
            ansatz,
            domains,
            8,
            LIMITS);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            plan,
            "x^2 + 8*x + 5",
            ansatz,
            domains,
            8));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            plan,
            source,
            "(x + ${shift})^2 - ${constant}",
            domains,
            8));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            plan,
            source,
            ansatz,
            List.of(
                HoleDomain.integerRange("shift", -4, 4),
                HoleDomain.integerRange("constant", -10, 10)),
            8));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            1));

        SchematicProofPlan limitSubstitution = SchematicProofPlan.create(
            plan.planId(),
            plan.informationBoundary(),
            plan.formationScopeHash(),
            plan.steps(),
            plan.holes(),
            plan.obligations(),
            new SchematicProofPlan.Limits(9, 9, 5, 250_000));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            limitSubstitution,
            source,
            ansatz,
            domains,
            8));

        Obligation original = plan.obligations().getFirst();
        Obligation substituted = new Obligation(
            original.id(),
            original.kind(),
            original.issuerStepId(),
            original.dependentHoleIds(),
            original.assumptions(),
            "different-checker",
            original.checkerRevisionHash(),
            original.initialStatus());
        SchematicProofPlan checkerSubstitution = SchematicProofPlan.create(
            plan.planId(),
            plan.informationBoundary(),
            plan.formationScopeHash(),
            plan.steps(),
            plan.holes(),
            List.of(substituted),
            plan.limits());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            checkerSubstitution,
            source,
            ansatz,
            domains,
            8));

        List<SchematicProofPlan.Step> extendedTopology = new java.util.ArrayList<>(
            plan.steps());
        extendedTopology.add(1, new SchematicProofPlan.Step(
            "select-domain-bindings",
            SchematicProofPlan.StepAction.SELECT_BINDINGS,
            plan.holeIds(),
            List.of()));
        SchematicProofPlan topologySubstitution = SchematicProofPlan.create(
            plan.planId(),
            plan.informationBoundary(),
            plan.formationScopeHash(),
            extendedTopology,
            plan.holes(),
            plan.obligations(),
            plan.limits());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            topologySubstitution,
            source,
            ansatz,
            domains,
            8));

        SchematicProofPlan.Hole originalHole = plan.holes().getFirst();
        List<SchematicProofPlan.Hole> changedHoles = new java.util.ArrayList<>(
            plan.holes());
        changedHoles.set(0, new SchematicProofPlan.Hole(
            originalHole.id(),
            originalHole.kind(),
            originalHole.sort(),
            originalHole.domainId(),
            "different-grammar/v1",
            originalHole.budget()));
        SchematicProofPlan holeSubstitution = SchematicProofPlan.create(
            plan.planId(),
            plan.informationBoundary(),
            plan.formationScopeHash(),
            plan.steps(),
            changedHoles,
            plan.obligations(),
            plan.limits());
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
            holeSubstitution,
            source,
            ansatz,
            domains,
            8));
    }

    @Test
    void rejectsForgedCandidateAndPlanRunLinkage() {
        String source = "x^2 + 6*x + 5";
        String ansatz = "(x + ${shift})^2 + ${constant}";
        List<HoleDomain> domains = quadraticDomains();
        SchematicProofPlan plan = resolver.createPlan(
            "tamper-control-plan",
            source,
            ansatz,
            domains,
            8,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            source,
            ansatz,
            domains,
            8);
        ExactFinitePolynomialResolvedCandidate candidate =
            run.candidates().getFirst();

        assertThrows(IllegalArgumentException.class, () ->
            new ExactFinitePolynomialResolvedCandidate(
                candidate.solution(),
                candidate.resolution(),
                candidate.solverResultHash(),
                hash("wrong-candidate")));
        assertThrows(IllegalArgumentException.class, () ->
            new ExactFinitePolynomialResolvedCandidate(
                candidate.solution(),
                candidate.resolution(),
                hash("wrong-solver"),
                candidate.contentHash()));

        List<HoleBinding> changedBindings = new java.util.ArrayList<>(
            candidate.resolution().bindings());
        HoleBinding originalBinding = changedBindings.getFirst();
        changedBindings.set(0, new HoleBinding(
            originalBinding.holeId(),
            originalBinding.sort(),
            originalBinding.canonicalValue(),
            hash("wrong-binding-evidence")));
        SchematicProofPlanResolution changedResolution =
            SchematicProofPlanResolution.create(
                plan,
                changedBindings,
                candidate.resolution().outcomes());
        assertThrows(IllegalArgumentException.class, () ->
            new ExactFinitePolynomialResolvedCandidate(
                candidate.solution(),
                changedResolution,
                candidate.solverResultHash(),
                candidate.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new ExactFinitePolynomialPlanRun(
                run.resolverId(),
                run.resolverRevisionHash(),
                run.planHash(),
                run.solverResult(),
                run.status(),
                List.of(),
                run.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new ExactFinitePolynomialPlanRun(
                run.resolverId(),
                run.resolverRevisionHash(),
                hash("different-plan"),
                run.solverResult(),
                run.status(),
                run.candidates(),
                run.contentHash()));
        assertThrows(IllegalArgumentException.class, () ->
            new ExactFinitePolynomialPlanRun(
                run.resolverId(),
                run.resolverRevisionHash(),
                run.planHash(),
                run.solverResult(),
                run.status(),
                run.candidates(),
                hash("wrong-run")));
    }

    private static List<HoleDomain> quadraticDomains() {
        return List.of(
            HoleDomain.integerRange("shift", -5, 5),
            HoleDomain.integerRange("constant", -10, 10));
    }

    private static String hash(String value) {
        return SchematicProofPlan.hash(value);
    }
}
