package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import org.junit.jupiter.api.Test;

class FiniteDifferenceSequenceDomainTest {
    private final FiniteDifferenceSequenceDomain domain =
        new FiniteDifferenceSequenceDomain();
    private final DomainDiscoveryRunner runner = new DomainDiscoveryRunner();
    private final DiscoveryBudget budget = new DiscoveryBudget(4, 20, 20, 10, 5, 20);

    @Test
    void discoversQuadraticFiniteDifferenceAndValidatesIndependentHoldout() {
        var seed = DiscoverySeed.create(
            "quadratic-squares",
            domain.domainId(),
            "observed=1,4,9,16;holdout=25,36",
            "test-corpus/quadratic-squares/v1");

        var run = runner.run("sequence-domain-confirmed", domain, seed, budget);

        assertEquals(Outcome.CONFIRMED, run.evidence().outcome());
        var candidate = run.selectedCandidate().orElseThrow();
        assertEquals(2, candidate.order());
        assertEquals(java.util.List.of(1L, 3L, 2L), candidate.initialDifferences());
        var certificate = run.selectedCertificate().orElseThrow();
        assertEquals(java.util.List.of(1L, 4L, 9L, 16L, 25L, 36L),
            certificate.generatedTerms());
        assertEquals("FINITE_DIFFERENCE_VALIDATION_NOT_FORMAL_PROOF",
            certificate.evidenceStrength());
        assertEquals("NOT_EVALUATED", run.evidence().proofStatus());
        assertEquals("NOT_EVALUATED", run.evidence().externalNoveltyStatus());
        assertEquals("NOT_EVALUATED", run.evidence().promotionStatus());
        assertEquals("NOT_EVALUATED", run.evidence().publicEvidenceStatus());
        assertTrue(run.evidence().transitions().stream().allMatch(
            DomainDiscoveryEvidence.TransitionTrace::semanticsPreserving));
        assertBalanced(run.evidence());
    }

    @Test
    void refutesWrongHoldoutWithoutRetainingCertificate() {
        var seed = DiscoverySeed.create(
            "quadratic-wrong-holdout",
            domain.domainId(),
            "observed=1,4,9,16;holdout=26",
            "test-corpus/quadratic-wrong-holdout/v1");

        var run = runner.run("sequence-domain-refuted", domain, seed, budget);

        assertEquals(Outcome.REFUTED, run.evidence().outcome());
        assertTrue(run.selectedCandidate().isEmpty());
        assertTrue(run.selectedCertificate().isEmpty());
        assertTrue(run.evidence().candidateAttempts().stream().anyMatch(attempt ->
            attempt.disposition()
                == DomainDiscoveryEvidence.AttemptDisposition.REFUTED_BY_EVALUATOR));
        assertFalse(run.evidence().toCanonicalJson().contains(
            "FINITE_DIFFERENCE_WITNESS"));
        assertBalanced(run.evidence());
    }

    @Test
    void counterexampleBudgetExhaustionCannotBecomeConfirmation() {
        var seed = DiscoverySeed.create(
            "quadratic-small-audit",
            domain.domainId(),
            "observed=1,4,9,16;holdout=25,36",
            "test-corpus/quadratic-small-audit/v1");
        var smallAudit = new DiscoveryBudget(4, 20, 20, 10, 5, 2);

        var run = runner.run("sequence-domain-small-audit", domain, seed, smallAudit);

        assertEquals(Outcome.INCONCLUSIVE, run.evidence().outcome());
        assertTrue(run.selectedCertificate().isEmpty());
        assertEquals(
            DomainDiscoveryEvidence.EvaluationDisposition.NOT_RUN,
            run.evidence().candidateAttempts().getFirst().evaluationStatus());
        assertBalanced(run.evidence());
    }

    private static void assertBalanced(DomainDiscoveryEvidence evidence) {
        evidence.resources().forEach(line -> assertEquals(
            line.configured(),
            line.executed() + line.skipped() + line.remaining(),
            line.resource().name()));
    }
}
