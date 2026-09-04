package example;

import static de.regelsuche.sdk.discovery.DiscoveryRunAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        assertThat(run)
            .isConfirmed()
            .hasCounterexampleCount(1)
            .hasCounterexampleContaining("multiplier 1")
            .candidateSatisfies(candidate ->
                assertEquals(2, candidate.multiplier()))
            .certificateSatisfies(certificate ->
                assertEquals(2, certificate.multiplier()))
            .hasContentAddressedEvidence();
    }

    @Test
    void refutesAnIncompatibleHoldout() {
        var run = run(
            "observed=2,4,8,16;holdout=33,66;maxMultiplier=4",
            DiscoveryBudgets.small(),
            "refuted"
        );

        assertThat(run).isRefuted();
    }

    @Test
    void exposesBudgetExhaustionInsteadOfReturningAPartialSuccess() {
        var run = run(
            "observed=2,4,8,16;holdout=32,64;maxMultiplier=6",
            DiscoveryBudgets.tiny(),
            "budget"
        );

        assertThat(run).isBudgetExhausted();
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
