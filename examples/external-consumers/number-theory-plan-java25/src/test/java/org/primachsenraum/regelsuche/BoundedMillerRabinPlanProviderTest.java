package org.primachsenraum.regelsuche;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.domain.DomainDiscoveryEvidence.Outcome;
import de.regelsuche.sdk.discovery.DiscoveryBudgets;
import de.regelsuche.sdk.discovery.DiscoveryRun;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoundedMillerRabinPlanProviderTest {
    @Test
    void findsAndExhaustivelyCertifiesBasesTwoAndThree() {
        var run = run(
            DiscoveryBudgets.of(3, 16, 32, 16, 16, 100_000),
            "success"
        );

        assertEquals(Outcome.CONFIRMED, run.outcome());
        assertEquals(List.of(2, 3), run.selectedCandidate().orElseThrow().bases());
        assertTrue(run.counterexamples().stream().anyMatch(
            witness -> witness.contains("2047")
        ));
        var certificate = run.selectedCertificate().orElseThrow();
        assertEquals(100_000, certificate.limit());
        assertEquals(0, certificate.falsePrimes());
        assertEquals(0, certificate.falseCompositeDecisions());
        assertEquals(certificate.oddComposites(), certificate.rejectedComposites());
    }

    @Test
    void independentlyRechecksThePublishedFinitePlan() {
        for (int value = 3; value <= 100_000; value += 2) {
            boolean expectedPrime = exactPrimeOracle(value);
            boolean accepted = BoundedMillerRabinPlanProvider.passesAllBases(
                value,
                List.of(2, 3)
            );
            assertEquals(expectedPrime, accepted, "value=" + value);
        }
    }

    @Test
    void acceptsAPrimeThatEqualsOneOfTheConfiguredBases() {
        assertTrue(BoundedMillerRabinPlanProvider.strongProbablePrime(5, 5));
        assertTrue(BoundedMillerRabinPlanProvider.passesAllBases(
            5,
            List.of(2, 3, 5)
        ));
    }

    @Test
    void exposesBudgetExhaustionWithoutACertificate() {
        var run = run(DiscoveryBudgets.tiny(), "budget");

        assertEquals(Outcome.BUDGET_EXHAUSTED, run.outcome());
        assertFalse(run.isConfirmed());
        assertTrue(run.selectedCertificate().isEmpty());
    }

    @Test
    void providerLoadsThroughTheExternalSpi() {
        var registration = RegelsucheDiscovery.loadDomains()
            .find(
                BoundedMillerRabinPlanProvider.DOMAIN_ID,
                BoundedMillerRabinPlanProvider.REVISION
            )
            .orElseThrow();

        assertEquals(
            "primachsenraum-number-theory-provider",
            registration.providerId()
        );
    }

    private static DiscoveryRun<
        BoundedMillerRabinPlanProvider.PlanCandidate,
        BoundedMillerRabinPlanProvider.PlanCertificate
    > run(
            de.regelsuche.discovery.domain.DiscoveryDomain.DiscoveryBudget budget,
            String suffix
    ) {
        return RegelsucheDiscovery
            .forDomain(BoundedMillerRabinPlanProvider.domain())
            .campaign("primachsenraum-bounded-mr-" + suffix)
            .seed(
                "bounded-mr-" + suffix,
                "limit=100000;maxBases=3",
                "primachsenraum-sdk-test"
            )
            .budget(budget)
            .run();
    }

    private static boolean exactPrimeOracle(int value) {
        if (value < 2) {
            return false;
        }
        for (int divisor = 2; (long) divisor * divisor <= value; divisor++) {
            if (value % divisor == 0) {
                return false;
            }
        }
        return true;
    }
}
