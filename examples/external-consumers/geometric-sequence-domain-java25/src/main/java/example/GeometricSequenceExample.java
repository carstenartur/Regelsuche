package example;

import de.regelsuche.sdk.discovery.DiscoveryBudgets;
import de.regelsuche.sdk.discovery.DiscoveryDomainCatalog;
import de.regelsuche.sdk.discovery.RegelsucheDiscovery;
import example.GeometricSequenceDomainProvider.Input;
import java.util.List;

public final class GeometricSequenceExample {
    private GeometricSequenceExample() {
    }

    public static void main(String[] args) {
        DiscoveryDomainCatalog catalog = RegelsucheDiscovery.loadDomains();
        var registration = catalog.find(
            GeometricSequenceDomainProvider.DOMAIN_ID,
            GeometricSequenceDomainProvider.REVISION
        ).orElseThrow();
        var input = new Input(List.of(2L, 4L, 8L, 16L), List.of(32L, 64L), 6);

        // A dynamic ServiceLoader catalog is the explicit serialized boundary.
        // Keep its host-observed provenance; do not replace this registration with a new domain.
        // Ordinary statically typed Java calls use forDomain(typedDomain()), as in the README.
        var run = RegelsucheDiscovery
            .forRegistration(registration)
            .campaign("external-geometric-sequence-demo")
            .seed(
                "powers-of-two",
                GeometricSequenceDomainProvider.INPUT_CODEC.encode(input),
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
