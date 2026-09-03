package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.math.algorithms.equivalence.ExactFinitePolynomialHoleSolver;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ExactFinitePolynomialRevisionBindingTest {
    @Test
    void propagatesTheTypedSolutionRevisionThroughResolverAndReplay() {
        String legacySolverRevision = legacySolverRevision();
        String currentSolverRevision =
            ExactFinitePolynomialHoleSolver.REVISION_HASH;

        assertNotEquals(legacySolverRevision, currentSolverRevision);

        String expectedCurrentResolver = resolverRevision(
            currentSolverRevision);
        String legacyResolver = resolverRevision(legacySolverRevision);
        assertEquals(
            expectedCurrentResolver,
            ExactFinitePolynomialPlanResolver.REVISION_HASH);
        assertNotEquals(
            legacyResolver,
            ExactFinitePolynomialPlanResolver.REVISION_HASH);

        assertEquals(
            replayVerifierRevision(
                currentSolverRevision,
                expectedCurrentResolver),
            ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH);
        assertNotEquals(
            replayVerifierRevision(
                legacySolverRevision,
                legacyResolver),
            ExactFinitePolynomialPlanReplayVerifier.REVISION_HASH);
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
