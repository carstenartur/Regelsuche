package example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.sdk.discovery.DiscoveryBudgets;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import org.junit.jupiter.api.Test;

class GeometricSequenceDomainTest {
    @Test
    void confirmsTheHeldOutMultiplierAndRetainsEarlierCounterexamples() {
        var run = run(
            "observed=2,4,8,16;holdout=32,64;maxMultiplier=6",
            DiscoveryBudgets.small(),
            "success"
        );

        assertEquals(Outcome.CONFIRMED, run.outcome());
        assertEquals(2, run.selectedCandidate().orElseThrow().multiplier());
        assertEquals(1, run.counterexamples().size());
        assertTrue(run.counterexamples().getFirst().contains("multiplier 1"));
    }

    @Test
    void refutesAnIncompatibleHoldout() {
        var run = run(
            "observed=2,4,8,16;holdout=33,66;maxMultiplier=4",
            DiscoveryBudgets.small(),
            "refuted"
        );

        assertEquals(Outcome.REFUTED, run.outcome());
        assertTrue(run.selectedCertificate().isEmpty());
    }

    @Test
    void exposesBudgetExhaustionInsteadOfReturningAPartialSuccess() {
        var run = run(
            "observed=2,4,8,16;holdout=32,64;maxMultiplier=6",
            DiscoveryBudgets.tiny(),
            "budget"
        );

        assertEquals(Outcome.BUDGET_EXHAUSTED, run.outcome());
        assertFalse(run.isConfirmed());
    }

    @Test
    void providerIsDiscoveredWithoutApplicationInternals() {
        var catalog = RegelsucheDiscovery.loadDomains();

        assertTrue(catalog.find(
            GeometricSequenceDomainProvider.DOMAIN_ID,
            GeometricSequenceDomainProvider.REVISION
        ).isPresent());
    }

    private static de.regelsuche.sdk.discovery.DiscoveryRun<
        GeometricSequenceDomainProvider.Plan,
        GeometricSequenceDomainProvider.Certificate
    > run(
            String payload,
            de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget budget,
            String suffix
    ) {
        return RegelsucheDiscovery
            .forDomain(GeometricSequenceDomainProvider.domain())
            .campaign("external-geometric-sequence-" + suffix)
            .seed("sequence-" + suffix, payload, "external-java25-test")
            .budget(budget)
            .run();
    }
}
