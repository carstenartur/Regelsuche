package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.discovery.domain.DiscoveryDomain;
import de.regelsuche.discovery.domain.DiscoveryDomain.*;
import de.regelsuche.sdk.discovery.*;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryDomainWorkbenchTest {
    @Test void noProviderIsEnabledByDefault() {
        var service = DiscoveryDomainWorkbench.forHost(getClass().getClassLoader(), "");
        assertTrue(service.catalogJson().contains("\"domains\":[]"));
        assertThrows(IllegalArgumentException.class, () -> service.run(input("small", "candidate")));
        assertThrows(IllegalArgumentException.class, () -> DiscoveryDomainWorkbench.forHost(
            getClass().getClassLoader(), "not.installed.Provider"));
    }

    @Test void runsOnlyHostSelectedDomainAndKeepsNegativeEvidence() {
        var service = new DiscoveryDomainWorkbench(DiscoveryDomainCatalog.fromProviders(List.of(new Provider())));
        assertTrue(service.catalogJson().contains("artifactSha256"));
        assertTrue(service.run(input("small", "candidate")).contains("\"outcome\":\"CONFIRMED\""));
        assertTrue(service.run(input("small", "wrong")).contains("\"outcome\":\"REFUTED\""));
        assertThrows(IllegalArgumentException.class, () -> service.run(input("unbounded", "candidate")));
        var arbitrary = new java.util.HashMap<String, Object>(input("small", "candidate"));
        arbitrary.put("providerClass", "remote.Untrusted");
        assertThrows(IllegalArgumentException.class, () -> service.run(arbitrary));
        arbitrary.remove("providerClass");
        arbitrary.put("providerId", "disabled");
        assertThrows(IllegalArgumentException.class, () -> service.run(arbitrary));
        arbitrary.put("domainId", 123);
        assertThrows(IllegalArgumentException.class, () -> service.run(arbitrary));
    }

    private static Map<String, Object> input(String budget, String seed) {
        return Map.of("providerId", "workbench-provider", "domainId", "workbench-domain", "revision", "v1",
            "campaignId", "workbench-test", "seed", seed, "budget", budget);
    }

    private static final class Provider implements DiscoveryDomainProvider {
        public String id() { return "workbench-provider"; }
        public Collection<DiscoveryDomain<?, ?, ?>> domains() {
            return List.of(DiscoveryDomainBuilder.<String, String, String>domain("workbench-domain", "v1")
                .generator(seed -> List.of(seed.payload())).stateCodec(value -> value)
                .invariant("text", value -> InvariantResult.pass()).operator("none", value -> List.of())
                .objective(value -> new ObjectiveAssessment(0, true, Map.of()))
                .candidate(context -> context.currentState(), value -> value)
                .counterexamples((value, budget) -> CounterexampleResult.noneFound(0, Map.of()))
                .evaluator(value -> value.equals("candidate")
                    ? Evaluation.confirmed("certificate", "exact fixture", Map.of())
                    : Evaluation.refuted("wrong candidate", Map.of()))
                .certificate("WORKBENCH_CERTIFICATE", value -> value, value -> value).build());
        }
    }
}
