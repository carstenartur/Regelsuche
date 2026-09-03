package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayStatus;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialPlanReplayVerifierTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayVerifier verifier =
        new ExactFinitePolynomialPlanReplayVerifier();

    @Test
    void issuesAReceiptOnlyAfterAnIdenticalCompleteQuadraticReplay() {
        List<HoleDomain> domains = List.of(
            HoleDomain.integerRange("shift", -5, 5),
            HoleDomain.integerRange("constant", -10, 10));
        SchematicProofPlan plan = resolver.createPlan(
            "quadratic-replay-plan",
            "x^2 + 6*x + 5",
            "(x + ${shift})^2 + ${constant}",
            domains,
            8,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            "x^2 + 6*x + 5",
            "(x + ${shift})^2 + ${constant}",
            domains,
            8);

        ReplayReceipt receipt = verifier.verify(
            plan,
            "x^2 + 6*x + 5",
            "(x + ${shift})^2 + ${constant}",
            domains,
            8,
            run);

        assertEquals(plan.contentHash(), receipt.planHash());
        assertEquals(run.contentHash(), receipt.planRunHash());
        assertEquals(run.solverResult().contentHash(),
            receipt.solverResultHash());
        assertEquals(231, receipt.totalAssignments());
        assertEquals(231, receipt.evaluatedAssignments());
        assertEquals(1, receipt.matchingAssignments());
        assertEquals(1, receipt.retainedSolutions());
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITH_RESOLUTIONS,
            receipt.runStatus());
        assertEquals(
            ReplayStatus.CONFIRMED_IDENTICAL_REPLAY,
            receipt.replayStatus());
        assertTrue(receipt.matches(plan, run));
        assertEquals(receipt, verifier.verify(
            plan,
            "x^2 + 6*x + 5",
            "(x + ${shift})^2 + ${constant}",
            domains,
            8,
            run));

        String json = receipt.toCanonicalJson();
        assertTrue(json.contains(receipt.contentHash()));
        assertFalse(json.contains("sourceExpression"));
        assertFalse(json.contains("ansatzTemplate"));
        assertFalse(json.contains("targetExpression"));
        assertFalse(json.contains("rewriteProgram"));
        assertFalse(json.contains("transformation"));
    }

    @Test
    void preservesCompleteNullAndTruncatedRunSemantics() {
        List<HoleDomain> noneDomains = List.of(
            HoleDomain.integerRange("shift", -2, 2));
        SchematicProofPlan nonePlan = resolver.createPlan(
            "null-replay-plan",
            "x^2 + 1",
            "(x + ${shift})^2",
            noneDomains,
            4,
            LIMITS);
        ExactFinitePolynomialPlanRun noneRun = resolver.resolve(
            nonePlan,
            "x^2 + 1",
            "(x + ${shift})^2",
            noneDomains,
            4);
        ReplayReceipt none = verifier.verify(
            nonePlan,
            "x^2 + 1",
            "(x + ${shift})^2",
            noneDomains,
            4,
            noneRun);
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION,
            none.runStatus());
        assertEquals(0, none.matchingAssignments());
        assertEquals(0, none.retainedSolutions());
        assertTrue(none.resolvedCandidateHashes().isEmpty());

        List<HoleDomain> signDomains = List.of(HoleDomain.signs("sign"));
        SchematicProofPlan truncatedPlan = resolver.createPlan(
            "truncated-replay-plan",
            "x^2",
            "(${sign}*x)^2",
            signDomains,
            1,
            LIMITS);
        ExactFinitePolynomialPlanRun truncatedRun = resolver.resolve(
            truncatedPlan,
            "x^2",
            "(${sign}*x)^2",
            signDomains,
            1);
        ReplayReceipt truncated = verifier.verify(
            truncatedPlan,
            "x^2",
            "(${sign}*x)^2",
            signDomains,
            1,
            truncatedRun);
        assertEquals(
            ExactFinitePolynomialPlanRun.Status.COMPLETE_RESOLUTION_SET_TRUNCATED,
            truncated.runStatus());
        assertEquals(2, truncated.matchingAssignments());
        assertEquals(1, truncated.retainedSolutions());
        assertEquals(1, truncated.resolvedCandidateHashes().size());
        assertFalse(none.equals(truncated));
    }

    @Test
    void rejectsChangedInputsPlanAndRunSubstitution() {
        List<HoleDomain> domains = List.of(
            HoleDomain.integerRange("constant", 0, 2));
        SchematicProofPlan plan = resolver.createPlan(
            "input-replay-plan",
            "x^2 + 1",
            "x^2 + ${constant}",
            domains,
            4,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            "x^2 + 1",
            "x^2 + ${constant}",
            domains,
            4);
        ReplayReceipt receipt = verifier.verify(
            plan,
            "x^2 + 1",
            "x^2 + ${constant}",
            domains,
            4,
            run);

        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            plan,
            "x^2 + 2",
            "x^2 + ${constant}",
            domains,
            4,
            run));
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            plan,
            "x^2 + 1",
            "x^2 + ${constant}",
            List.of(HoleDomain.integerRange("constant", 1, 2)),
            4,
            run));

        SchematicProofPlan differentPlan = resolver.createPlan(
            "different-replay-plan",
            "x^2 + 1",
            "x^2 + ${constant}",
            domains,
            4,
            LIMITS);
        ExactFinitePolynomialPlanRun differentRun = resolver.resolve(
            differentPlan,
            "x^2 + 1",
            "x^2 + ${constant}",
            domains,
            4);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            differentPlan,
            "x^2 + 1",
            "x^2 + ${constant}",
            domains,
            4,
            run));
        assertFalse(receipt.matches(differentPlan, differentRun));
    }

    @Test
    void bindsTypedSolutionRevisionThroughPlanRunAndReplayReceipt() {
        String source = "x + 1";
        String ansatz = "x + ${unit}";
        List<HoleDomain> domains = List.of(
            HoleDomain.integerRange("unit", 1, 1));
        int retainedSolutionLimit = 4;

        String legacySolverRevision = legacySolverRevision();
        String currentSolverRevision =
            ExactFinitePolynomialHoleSolver.REVISION_HASH;
        assertNotEquals(legacySolverRevision, currentSolverRevision);

        String currentResolverRevision = resolverRevision(
            currentSolverRevision);
        String legacyResolverRevision = resolverRevision(
            legacySolverRevision);
        assertEquals(
            currentResolverRevision,
            ExactFinitePolynomialPlanResolver.REVISION_HASH);
        assertNotEquals(
            legacyResolverRevision,
            currentResolverRevision);

        String currentVerifierRevision = replayVerifierRevision(
            currentSolverRevision,
            currentResolverRevision);
        String legacyVerifierRevision = replayVerifierRevision(
            legacySolverRevision,
            legacyResolverRevision);
        assertEquals(
            currentVerifierRevision,
            ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH);
        assertNotEquals(
            legacyVerifierRevision,
            currentVerifierRevision);

        SchematicProofPlan currentPlan = resolver.createPlan(
            "typed-solution-revision-plan",
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            LIMITS);
        HoleDomain domain = domains.getFirst();
        String legacyScopeHash = SchematicProofPlan.hash(lengthPrefixed(
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            legacyResolverRevision,
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            legacySolverRevision,
            currentPlan.planId(),
            Integer.toString(LIMITS.maxSteps()),
            Integer.toString(LIMITS.maxHoles()),
            Integer.toString(LIMITS.maxObligations()),
            Integer.toString(LIMITS.maxCanonicalBytes()),
            source,
            ansatz,
            Integer.toString(retainedSolutionLimit),
            domain.holeId(),
            domain.kind().name(),
            domain.values().getFirst().canonicalText()));
        assertNotEquals(
            legacyScopeHash,
            currentPlan.formationScopeHash());

        SchematicProofPlan.Obligation currentObligation =
            currentPlan.obligations().getFirst();
        SchematicProofPlan.Obligation legacyObligation =
            new SchematicProofPlan.Obligation(
                currentObligation.id(),
                currentObligation.kind(),
                currentObligation.issuerStepId(),
                currentObligation.dependentHoleIds(),
                currentObligation.assumptions(),
                currentObligation.checkerCapability(),
                legacySolverRevision,
                currentObligation.initialStatus());
        SchematicProofPlan legacyPlan = SchematicProofPlan.create(
            currentPlan.planId(),
            currentPlan.informationBoundary(),
            legacyScopeHash,
            currentPlan.steps(),
            currentPlan.holes(),
            List.of(legacyObligation),
            currentPlan.limits());
        assertNotEquals(
            legacyPlan.contentHash(),
            currentPlan.contentHash());

        ExactFinitePolynomialPlanRun currentRun = resolver.resolve(
            currentPlan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit);
        ReplayReceipt currentReceipt = verifier.verify(
            currentPlan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            currentRun);
        assertEquals(
            currentSolverRevision,
            currentRun.solverResult().solverRevisionHash());
        assertEquals(
            currentResolverRevision,
            currentRun.resolverRevisionHash());
        assertEquals(
            currentSolverRevision,
            currentReceipt.solverRevisionHash());
        assertEquals(
            currentVerifierRevision,
            currentReceipt.verifierRevisionHash());
    }

    @Test
    void receiptImplementationIsSealedPrivateAndVerifierOwned() {
        assertTrue(ReplayReceipt.class.isSealed());
        Class<?>[] permitted = ReplayReceipt.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        assertTrue(Modifier.isPrivate(permitted[0].getModifiers()));
        assertTrue(Arrays.stream(permitted[0].getDeclaredConstructors())
            .allMatch(constructor ->
                Modifier.isPrivate(constructor.getModifiers())));
    }

    private static String legacySolverRevision() {
        return SchematicProofPlan.hash(lengthPrefixed(
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            "source-exact-polynomial-arithmetic",
            "complete-finite-cartesian-enumeration",
            "coefficient-and-sign-holes",
            "unsupported-instantiation-fails-closed"));
    }

    private static String resolverRevision(String solverRevision) {
        return SchematicProofPlan.hash(
            ExactFinitePolynomialPlanResolver.RESOLVER_ID
                + "|solver=" + solverRevision
                + "|plan=" + SchematicProofPlan.SCHEMA
                + "|resolution=" + SchematicProofPlanResolution.SCHEMA
                + "|topology=finite-domains-solve-discharge-emit"
                + "|evidence=solution-binding-and-equivalence-outcome");
    }

    private static String replayVerifierRevision(
        String solverRevision,
        String resolverRevision
    ) {
        return SchematicProofPlan.hash(lengthPrefixed(
            ExactFinitePolynomialPlanReplayVerifier.VERIFIER_ID,
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            resolverRevision,
            ExactFinitePolynomialHoleSolver.SOLVER_ID,
            solverRevision,
            "complete-run-reexecution",
            "exact-plan-run-equality",
            "sealed-verifier-owned-non-executable-receipt"));
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
}
