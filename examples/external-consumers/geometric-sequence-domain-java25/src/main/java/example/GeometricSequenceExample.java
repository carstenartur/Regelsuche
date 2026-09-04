package example;

import de.regelsuche.sdk.discovery.DiscoveryBudgets;
import de.regelsuche.sdk.discovery.DiscoveryDomainCatalog;
import de.regelsuche.sdk.discovery.DiscoveryRun;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;

public final class GeometricSequenceExample {
    private GeometricSequenceExample() {
    }

    public static void main(String[] args) {
        DiscoveryDomainCatalog catalog = RegelsucheDiscovery.loadDomains();
        var registration = catalog.find(
            GeometricSequenceDomainProvider.DOMAIN_ID,
            GeometricSequenceDomainProvider.REVISION
        ).orElseThrow();

        DiscoveryRun<
            GeometricSequenceDomainProvider.Plan,
            GeometricSequenceDomainProvider.Certificate
        > run = RegelsucheDiscovery
            .forDomain(GeometricSequenceDomainProvider.domain())
            .campaign("external-geometric-sequence-demo")
            .seed(
                "powers-of-two",
                "observed=2,4,8,16;holdout=32,64;maxMultiplier=6",
                "external-java25-example"
            )
            .budget(DiscoveryBudgets.small())
            .run();

        System.out.println("provider=" + registration.providerId());
        System.out.println("outcome=" + run.outcome());
        System.out.println("candidate=" + run.selectedCandidate().orElseThrow());
        System.out.println("counterexamples=" + run.counterexamples());
        System.out.println("evidenceHash=" + run.evidence().contentHash());
    }
}
