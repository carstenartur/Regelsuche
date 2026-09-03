package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayReceipt;
import de.regelsuche.evolution.ExactFinitePolynomialPlanReplayVerifier.ReplayStatus;
import de.regelsuche.evolution.SchematicProofPlan.Obligation;
import de.regelsuche.evolution.SchematicProofPlanResolution.HoleBinding;
import de.regelsuche.evolution.SchematicProofPlanResolution.ObligationOutcome;
import de.regelsuche.evolution.SchematicProofPlanResolution.OutcomeStatus;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleDomain;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.HoleKind;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.SearchResult;
import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver.Solution;
import de.regelsuche.scalar.ExactRational;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialRevisionBindingTest {
    private static final SchematicProofPlan.Limits LIMITS =
        new SchematicProofPlan.Limits(8, 8, 4, 200_000);

    private final ExactFinitePolynomialPlanResolver resolver =
        new ExactFinitePolynomialPlanResolver();
    private final ExactFinitePolynomialPlanReplayVerifier verifier =
        new ExactFinitePolynomialPlanReplayVerifier();

    @Test
    void propagatesTypedSolutionIdentityThroughEveryReplayHashLayer() {
        String source = "x + 1";
        String ansatz = "x + ${unit}";
        List<HoleDomain> domains = List.of(new HoleDomain(
            "unit",
            HoleKind.COEFFICIENT,
            List.of(ExactRational.ONE)));
        int retainedSolutionLimit = 4;

        String currentSolverRevision =
            ExactFinitePolynomialHoleSolver.REVISION_HASH;
        String legacySolverRevision = legacySolverRevision();
        String currentResolverRevision = resolverRevision(
            currentSolverRevision);
        String legacyResolverRevision = resolverRevision(
            legacySolverRevision);
        String currentVerifierRevision = replayVerifierRevision(
            currentSolverRevision,
            currentResolverRevision);
        String legacyVerifierRevision = replayVerifierRevision(
            legacySolverRevision,
            legacyResolverRevision);

        assertEquals(
            currentResolverRevision,
            ExactFinitePolynomialPlanResolver.REVISION_HASH);
        assertEquals(
            currentVerifierRevision,
            ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH);
        assertNotEquals(legacySolverRevision, currentSolverRevision);
        assertNotEquals(legacyResolverRevision, currentResolverRevision);
        assertNotEquals(legacyVerifierRevision, currentVerifierRevision);

        SchematicProofPlan currentPlan = resolver.createPlan(
            "typed-solution-revision-plan",
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            LIMITS);
        String currentScopeHash = formationScopeHash(
            currentPlan.planId(),
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            LIMITS,
            currentResolverRevision,
            currentSolverRevision);
        String legacyScopeHash = formationScopeHash(
            currentPlan.planId(),
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            LIMITS,
            legacyResolverRevision,
            legacySolverRevision);
        assertEquals(currentScopeHash, currentPlan.formationScopeHash());
        assertNotEquals(legacyScopeHash, currentScopeHash);

        SchematicProofPlan legacyPlan = legacyPlan(
            currentPlan,
            legacyScopeHash,
            legacySolverRevision);
        assertNotEquals(currentPlan.contentHash(), legacyPlan.contentHash());

        ExactFinitePolynomialPlanRun currentRun = resolver.resolve(
            currentPlan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit);
        SearchResult currentSolverResult = currentRun.solverResult();
        String currentSolverResultHash = solverResultHash(
            currentSolverResult,
            currentSolverRevision,
            false);
        String legacySolverResultHash = solverResultHash(
            currentSolverResult,
            legacySolverRevision,
            true);
        assertEquals(
            currentSolverResult.contentHash(),
            currentSolverResultHash);
        assertNotEquals(
            legacySolverResultHash,
            currentSolverResultHash);

        ExactFinitePolynomialResolvedCandidate currentCandidate =
            currentRun.candidates().getFirst();
        Solution currentSolution = currentCandidate.solution();
        String legacySolutionHash = legacySolutionHash(currentSolution);
        assertNotEquals(
            legacySolutionHash,
            currentSolution.contentHash());

        SchematicProofPlanResolution legacyResolution = legacyResolution(
            legacyPlan,
            currentCandidate,
            legacyResolverRevision,
            legacySolverRevision,
            legacySolverResultHash,
            legacySolutionHash);
        String currentCandidateHash = candidateHash(
            currentResolverRevision,
            currentPlan.contentHash(),
            currentSolverResultHash,
            currentSolution.contentHash(),
            currentCandidate.resolution().contentHash());
        String legacyCandidateHash = candidateHash(
            legacyResolverRevision,
            legacyPlan.contentHash(),
            legacySolverResultHash,
            legacySolutionHash,
            legacyResolution.contentHash());
        assertEquals(currentCandidate.contentHash(), currentCandidateHash);
        assertNotEquals(legacyCandidateHash, currentCandidateHash);

        String currentPlanRunHash = planRunHash(
            currentResolverRevision,
            currentPlan.contentHash(),
            currentSolverResultHash,
            currentRun.status(),
            List.of(currentCandidateHash));
        String legacyPlanRunHash = planRunHash(
            legacyResolverRevision,
            legacyPlan.contentHash(),
            legacySolverResultHash,
            currentRun.status(),
            List.of(legacyCandidateHash));
        assertEquals(currentRun.contentHash(), currentPlanRunHash);
        assertNotEquals(legacyPlanRunHash, currentPlanRunHash);

        ReplayReceipt currentReceipt = verifier.verify(
            currentPlan,
            source,
            ansatz,
            domains,
            retainedSolutionLimit,
            currentRun);
        String currentReceiptHash = receiptHash(
            currentVerifierRevision,
            currentPlan.contentHash(),
            currentPlanRunHash,
            currentSolverResultHash,
            currentSolverRevision,
            currentRun,
            List.of(currentCandidateHash));
        String legacyReceiptHash = receiptHash(
            legacyVerifierRevision,
            legacyPlan.contentHash(),
            legacyPlanRunHash,
            legacySolverResultHash,
            legacySolverRevision,
            currentRun,
            List.of(legacyCandidateHash));
        assertEquals(currentReceipt.contentHash(), currentReceiptHash);
        assertNotEquals(legacyReceiptHash, currentReceiptHash);
    }

    private static SchematicProofPlan legacyPlan(
        SchematicProofPlan current,
        String legacyScopeHash,
        String legacySolverRevision
    ) {
        Obligation currentObligation = current.obligations().getFirst();
        Obligation legacyObligation = new Obligation(
            currentObligation.id(),
            currentObligation.kind(),
            currentObligation.issuerStepId(),
            currentObligation.dependentHoleIds(),
            currentObligation.assumptions(),
            currentObligation.checkerCapability(),
            legacySolverRevision,
            currentObligation.initialStatus());
        return SchematicProofPlan.create(
            current.planId(),
            current.informationBoundary(),
            legacyScopeHash,
            current.steps(),
            current.holes(),
            List.of(legacyObligation),
            current.limits());
    }

    private static SchematicProofPlanResolution legacyResolution(
        SchematicProofPlan legacyPlan,
        ExactFinitePolynomialResolvedCandidate currentCandidate,
        String legacyResolverRevision,
        String legacySolverRevision,
        String legacySolverResultHash,
        String legacySolutionHash
    ) {
        HoleBinding currentBinding =
            currentCandidate.resolution().bindings().getFirst();
        HoleBinding legacyBinding = new HoleBinding(
            currentBinding.holeId(),
            currentBinding.sort(),
            currentBinding.canonicalValue(),
            bindingEvidenceHash(
                legacyResolverRevision,
                legacyPlan.contentHash(),
                legacySolverResultHash,
                legacySolutionHash,
                currentBinding.holeId(),
                currentBinding.canonicalValue()));
        Obligation obligation = legacyPlan.obligations().getFirst();
        ObligationOutcome legacyOutcome = new ObligationOutcome(
            obligation.id(),
            OutcomeStatus.CONFIRMED,
            obligation.checkerCapability(),
            legacySolverRevision,
            legacySolverResultHash,
            "EXACT_FINITE_POLYNOMIAL_EQUIVALENCE_CONFIRMED");
        return SchematicProofPlanResolution.create(
            legacyPlan,
            List.of(legacyBinding),
            List.of(legacyOutcome));
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

    private static String formationScopeHash(
        String planId,
        String sourceExpression,
        String ansatzTemplate,
        List<HoleDomain> domains,
        int retainedSolutionLimit,
        SchematicProofPlan.Limits limits,
        String resolverRevision,
        String solverRevision
    ) {
        StringBuilder material = new StringBuilder();
        append(material, ExactFinitePolynomialPlanResolver.RESOLVER_ID);
        append(material, resolverRevision);
        append(material, ExactFinitePolynomialHoleSolver.SOLVER_ID);
        append(material, solverRevision);
        append(material, planId);
        append(material, Integer.toString(limits.maxSteps()));
        append(material, Integer.toString(limits.maxHoles()));
        append(material, Integer.toString(limits.maxObligations()));
        append(material, Integer.toString(limits.maxCanonicalBytes()));
        append(material, sourceExpression.strip().replaceAll("\\s+", " "));
        append(material, ansatzTemplate.strip().replaceAll("\\s+", " "));
        append(material, Integer.toString(retainedSolutionLimit));
        domains.stream()
            .sorted(Comparator.comparing(HoleDomain::holeId))
            .forEach(domain -> {
                append(material, domain.holeId());
                append(material, domain.kind().name());
                domain.values().forEach(value ->
                    append(material, value.canonicalText()));
            });
        return SchematicProofPlan.hash(material.toString());
    }

    private static String solverResultHash(
        SearchResult result,
        String solverRevision,
        boolean legacySolutionIdentity
    ) {
        StringBuilder material = new StringBuilder();
        append(material, result.solverId());
        append(material, solverRevision);
        append(material, result.sourceExpression());
        append(material, result.ansatzTemplate());
        append(material, Long.toString(result.totalAssignments()));
        append(material, Long.toString(result.evaluatedAssignments()));
        append(material, Long.toString(result.matchingAssignments()));
        append(material, Integer.toString(result.retainedSolutionLimit()));
        append(material, result.status().name());
        for (HoleDomain domain : result.holeDomains()) {
            append(material, domain.holeId());
            append(material, domain.kind().name());
            for (ExactRational value : domain.values()) {
                append(material, value.canonicalText());
            }
        }
        for (Solution solution : result.solutions()) {
            append(
                material,
                legacySolutionIdentity
                    ? legacySolutionHash(solution)
                    : solution.contentHash());
        }
        return SchematicProofPlan.hash(material.toString());
    }

    private static String legacySolutionHash(Solution solution) {
        return SchematicProofPlan.hash(lengthPrefixed(
            solution.bindingKey(),
            solution.instantiatedExpression(),
            solution.exactNormalForm()));
    }

    private static String bindingEvidenceHash(
        String resolverRevision,
        String planHash,
        String solverResultHash,
        String solutionHash,
        String holeId,
        String canonicalValue
    ) {
        return SchematicProofPlan.hash(lengthPrefixed(
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            resolverRevision,
            planHash,
            solverResultHash,
            solutionHash,
            holeId,
            canonicalValue));
    }

    private static String candidateHash(
        String resolverRevision,
        String planHash,
        String solverResultHash,
        String solutionHash,
        String resolutionHash
    ) {
        return SchematicProofPlan.hash(lengthPrefixed(
            ExactFinitePolynomialPlanResolver.RESOLVER_ID,
            resolverRevision,
            planHash,
            solverResultHash,
            solutionHash,
            resolutionHash));
    }

    private static String planRunHash(
        String resolverRevision,
        String planHash,
        String solverResultHash,
        ExactFinitePolynomialPlanRun.Status status,
        List<String> candidateHashes
    ) {
        StringBuilder material = new StringBuilder();
        append(material, ExactFinitePolynomialPlanResolver.RESOLVER_ID);
        append(material, resolverRevision);
        append(material, planHash);
        append(material, solverResultHash);
        append(material, status.name());
        candidateHashes.stream().sorted().forEach(hash ->
            append(material, hash));
        return SchematicProofPlan.hash(material.toString());
    }

    private static String receiptHash(
        String verifierRevision,
        String planHash,
        String planRunHash,
        String solverResultHash,
        String solverRevision,
        ExactFinitePolynomialPlanRun run,
        List<String> candidateHashes
    ) {
        List<String> sortedCandidateHashes = candidateHashes.stream()
            .sorted()
            .toList();
        String payload = new JsonWriter().beginObject()
            .property("schema", ReplayReceipt.SCHEMA)
            .property(
                "verifierId",
                ExactFinitePolynomialPlanReplayVerifier.VERIFIER_ID)
            .property("verifierRevisionHash", verifierRevision)
            .property("planHash", planHash)
            .property("planRunHash", planRunHash)
            .property("solverResultHash", solverResultHash)
            .property("solverRevisionHash", solverRevision)
            .property("runStatus", run.status().name())
            .property("totalAssignments", run.solverResult().totalAssignments())
            .property(
                "evaluatedAssignments",
                run.solverResult().evaluatedAssignments())
            .property(
                "matchingAssignments",
                run.solverResult().matchingAssignments())
            .property("retainedSolutions", sortedCandidateHashes.size())
            .stringArray(
                "resolvedCandidateHashes",
                sortedCandidateHashes)
            .property(
                "replayStatus",
                ReplayStatus.CONFIRMED_IDENTICAL_REPLAY.name())
            .endObject()
            .toString();
        return SchematicProofPlan.hash(payload);
    }

    private static String lengthPrefixed(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            append(result, value);
        }
        return result.toString();
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length)
            .append(':')
            .append(value);
    }
}
