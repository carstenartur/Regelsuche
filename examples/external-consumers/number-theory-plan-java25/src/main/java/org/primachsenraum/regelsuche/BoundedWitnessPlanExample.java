package org.primachsenraum.regelsuche;

import de.regelsuche.sdk.discovery.DiscoveryBudgets;
import de.regelsuche.sdk.discovery.DiscoveryRun;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;

public final class BoundedWitnessPlanExample {
    private BoundedWitnessPlanExample() {
    }

    public static void main(String[] args) {
        var registration = RegelsucheDiscovery.loadDomains()
            .find(
                BoundedMillerRabinPlanProvider.DOMAIN_ID,
                BoundedMillerRabinPlanProvider.REVISION
            )
            .orElseThrow();

        DiscoveryRun<
            BoundedMillerRabinPlanProvider.PlanCandidate,
            BoundedMillerRabinPlanProvider.PlanCertificate
        > run = RegelsucheDiscovery
            .forDomain(BoundedMillerRabinPlanProvider.domain())
            .campaign("primachsenraum-bounded-mr-sdk-consumer")
            .seed(
                "bounded-mr-100000",
                "limit=100000;maxBases=3",
                "primachsenraum-external-consumer"
            )
            .budget(DiscoveryBudgets.of(3, 16, 32, 16, 16, 100_000))
            .run();

        System.out.println("provider=" + registration.providerId());
        System.out.println("outcome=" + run.outcome());
        System.out.println("bases=" + run.selectedCandidate().orElseThrow().bases());
        System.out.println("counterexamples=" + run.counterexamples());
        System.out.println("certificate=" + run.selectedCertificate().orElseThrow().canonical());
        System.out.println("evidenceHash=" + run.evidence().contentHash());
    }
}
