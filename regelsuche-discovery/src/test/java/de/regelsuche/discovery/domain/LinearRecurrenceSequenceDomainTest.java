package de.regelsuche.discovery.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget;
import de.regelsuche.discovery.domain.DiscoveryDomain.DiscoverySeed;
import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearRecurrenceSequenceDomainTest {
    private final LinearRecurrenceSequenceDomain domain =
        new LinearRecurrenceSequenceDomain();
    private final DomainDiscoveryRunner runner = new DomainDiscoveryRunner();
    private final DiscoveryBudget budget = new DiscoveryBudget(4, 20, 20, 10, 8, 20);

    @Test
    void discoversFibonacciRecurrenceAndValidatesIndependentHoldout() {
        var seed = DiscoverySeed.create(
            "fibonacci-like",
            domain.domainId(),
            "observed=2,3,5,8,13,21;holdout=34,55,89;maximumOrder=3",
            "test-corpus/fibonacci-like/v1");

        var run = runner.run("linear-recurrence-confirmed", domain, seed, budget);

        assertEquals(Outcome.CONFIRMED, run.evidence().outcome());
        var candidate = run.selectedCandidate().orElseThrow();
        assertEquals(2, candidate.model().order());
        assertEquals(List.of(
            new LinearRecurrenceSequenceDomain.Rational(BigInteger.ONE, BigInteger.ONE),
            new LinearRecurrenceSequenceDomain.Rational(BigInteger.ONE, BigInteger.ONE)),
            candidate.model().coefficients());
        var certificate = run.selectedCertificate().orElseThrow();
        assertEquals(List.of("2", "3", "5", "8", "13", "21", "34", "55", "89"),
            certificate.generatedTerms());
        assertEquals(
            "LINEAR_RECURRENCE_FINITE_DATA_VALIDATION_NOT_FORMAL_PROOF",
            certificate.evidenceStrength());
        assertEquals("NOT_EVALUATED", run.evidence().proofStatus());
        assertEquals("NOT_EVALUATED", run.evidence().externalNoveltyStatus());
        assertBalanced(run.evidence());
    }

    @Test
    void discoversThirdOrderRecurrenceForNonhomogeneousPresentation() {
        var model = LinearRecurrenceSequenceDomain.inferUniqueRecurrence(
            List.of(1L, 4L, 11L, 26L, 57L, 120L), 4).orElseThrow();

        assertEquals(3, model.order());
        assertEquals("[4,-5,2]", model.canonicalForm().split("coefficients=")[1]);
        assertEquals("247", model.predictNext(
            List.of(1L, 4L, 11L, 26L, 57L, 120L)).canonical());
    }

    @Test
    void refutesObservedFitThatFailsFrozenHoldout() {
        var seed = DiscoverySeed.create(
            "cubic-finite-prefix",
            domain.domainId(),
            "observed=2,10,30,68,130,222;holdout=350,520,738;maximumOrder=4",
            "test-corpus/cubic-finite-prefix/v1");

        var run = runner.run("linear-recurrence-refuted", domain, seed, budget);

        assertEquals(Outcome.REFUTED, run.evidence().outcome());
        assertTrue(run.selectedCandidate().isEmpty());
        assertTrue(run.selectedCertificate().isEmpty());
        assertTrue(run.evidence().candidateAttempts().stream().anyMatch(attempt ->
            attempt.disposition()
                == DomainDiscoveryEvidence.AttemptDisposition.REFUTED_BY_EVALUATOR));
        assertFalse(run.evidence().toCanonicalJson().contains(
            "LINEAR_RECURRENCE_WITNESS"));
        assertBalanced(run.evidence());
    }

    @Test
    void insufficientSupportDoesNotInventARecurrence() {
        var seed = DiscoverySeed.create(
            "squares-insufficient-linear-support",
            domain.domainId(),
            "observed=1,4,9,16,25;holdout=36,49,64;maximumOrder=4",
            "test-corpus/squares-insufficient-linear-support/v1");

        var run = runner.run("linear-recurrence-no-model", domain, seed, budget);

        assertEquals(Outcome.INCONCLUSIVE, run.evidence().outcome());
        assertTrue(run.selectedCandidate().isEmpty());
        assertTrue(run.evidence().candidateAttempts().isEmpty());
        assertBalanced(run.evidence());
    }

    private static void assertBalanced(DomainDiscoveryEvidence evidence) {
        evidence.resources().forEach(line -> assertEquals(
            line.configured(),
            line.executed() + line.skipped() + line.remaining(),
            line.resource().name()));
    }
}
