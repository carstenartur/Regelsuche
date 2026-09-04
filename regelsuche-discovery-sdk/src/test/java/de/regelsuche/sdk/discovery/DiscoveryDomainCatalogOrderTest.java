package de.regelsuche.sdk.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.CounterexampleResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.Evaluation;
import de.regelsuche.discovery.domain.DiscoveryDomain.InvariantResult;
import de.regelsuche.discovery.domain.DiscoveryDomain.ObjectiveAssessment;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryDomainCatalogOrderTest {
    @Test
    void normalizesProviderAndDomainIterationOrder() {
        DiscoveryDomainProvider laterProvider = provider(
            "z-provider",
            List.of(domain("z-domain"), domain("a-domain"))
        );
        DiscoveryDomainProvider earlierProvider = provider(
            "a-provider",
            List.of(domain("m-domain"))
        );

        DiscoveryDomainCatalog catalog = DiscoveryDomainCatalog.fromProviders(
            List.of(laterProvider, earlierProvider)
        );

        assertEquals(
            List.of(
                "a-provider:m-domain@v1",
                "z-provider:a-domain@v1",
                "z-provider:z-domain@v1"
            ),
            catalog.registrations().stream()
                .map(registration -> registration.providerId() + ":"
                    + registration.domain().domainId() + "@"
                    + registration.domain().revision())
                .toList()
        );
    }

    private static DiscoveryDomainProvider provider(
            String id,
            Collection<DiscoveryDomain<?, ?, ?>> domains
    ) {
        return new DiscoveryDomainProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Collection<DiscoveryDomain<?, ?, ?>> domains() {
                return domains;
            }
        };
    }

    private static DiscoveryDomain<Integer, Integer, String> domain(String id) {
        return DiscoveryDomainBuilder.<Integer, Integer, String>domain(id, "v1")
            .generator(seed -> List.of(0))
            .stateCodec(Object::toString)
            .invariant("non-negative", state -> state >= 0
                ? InvariantResult.pass()
                : InvariantResult.fail("negative-state"))
            .operator("terminal", state -> List.of())
            .objective(state -> new ObjectiveAssessment(
                1,
                true,
                Map.of("state", Integer.toString(state))
            ))
            .candidate(context -> context.currentState(), Object::toString)
            .counterexamples((candidate, budget) ->
                CounterexampleResult.noneFound(0, Map.of()))
            .evaluator(candidate -> Evaluation.confirmed(
                "certificate-" + candidate,
                "test evaluator",
                Map.of()
            ))
            .certificate(
                "TEST_CERTIFICATE",
                certificate -> certificate,
                certificate -> certificate
            )
            .build();
    }
}
