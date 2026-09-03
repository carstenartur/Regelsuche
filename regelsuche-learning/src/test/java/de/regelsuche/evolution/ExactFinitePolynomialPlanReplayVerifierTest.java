package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayStatus;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
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
    }

    @Test
    void rejectsChangedInputsPlanRunsAndReceiptHashes() {
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
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(
            differentPlan,
            "x^2 + 1",
            "x^2 + ${constant}",
            domains,
            4,
            run));
        assertFalse(receipt.matches(differentPlan, run));

        assertThrows(IllegalArgumentException.class, () ->
            new ReplayReceipt(
                receipt.schema(),
                receipt.verifierId(),
                receipt.verifierRevisionHash(),
                receipt.planHash(),
                receipt.planRunHash(),
                receipt.solverResultHash(),
                receipt.solverRevisionHash(),
                receipt.runStatus(),
                receipt.totalAssignments(),
                receipt.evaluatedAssignments(),
                receipt.matchingAssignments(),
                receipt.retainedSolutions(),
                receipt.resolvedCandidateHashes(),
                receipt.replayStatus(),
                SchematicProofPlan.hash("forged-receipt")));
    }

    @Test
    void rejectsInconsistentStatusCountsAndDuplicateCandidateHashes() {
        List<HoleDomain> domains = List.of(HoleDomain.signs("sign"));
        SchematicProofPlan plan = resolver.createPlan(
            "status-replay-plan",
            "x^2",
            "(${sign}*x)^2",
            domains,
            2,
            LIMITS);
        ExactFinitePolynomialPlanRun run = resolver.resolve(
            plan,
            "x^2",
            "(${sign}*x)^2",
            domains,
            2);
        ReplayReceipt receipt = verifier.verify(
            plan,
            "x^2",
            "(${sign}*x)^2",
            domains,
            2,
            run);

        assertThrows(IllegalArgumentException.class, () ->
            new ReplayReceipt(
                receipt.schema(),
                receipt.verifierId(),
                receipt.verifierRevisionHash(),
                receipt.planHash(),
                receipt.planRunHash(),
                receipt.solverResultHash(),
                receipt.solverRevisionHash(),
                ExactFinitePolynomialPlanRun.Status.COMPLETE_WITHOUT_SOLUTION,
                receipt.totalAssignments(),
                receipt.evaluatedAssignments(),
                receipt.matchingAssignments(),
                receipt.retainedSolutions(),
                receipt.resolvedCandidateHashes(),
                receipt.replayStatus(),
                receipt.contentHash()));
        String hash = receipt.resolvedCandidateHashes().getFirst();
        assertThrows(IllegalArgumentException.class, () ->
            new ReplayReceipt(
                receipt.schema(),
                receipt.verifierId(),
                receipt.verifierRevisionHash(),
                receipt.planHash(),
                receipt.planRunHash(),
                receipt.solverResultHash(),
                receipt.solverRevisionHash(),
                receipt.runStatus(),
                receipt.totalAssignments(),
                receipt.evaluatedAssignments(),
                receipt.matchingAssignments(),
                2,
                List.of(hash, hash),
                receipt.replayStatus(),
                receipt.contentHash()));
    }
}
