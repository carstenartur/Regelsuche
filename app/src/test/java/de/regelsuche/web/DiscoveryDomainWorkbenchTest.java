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
    @Test void httpRoutesRequireAuthenticationAndHonorTheHostsBodyLimit() throws Exception {
        var server = new WebWorkbenchServer("127.0.0.1", 0,
            new de.regelsuche.graph.InMemoryExpressionGraphStore(),
            new de.regelsuche.inventory.InMemoryRuleInventoryRepository(),
            new de.regelsuche.export.DefaultTransformationExportService(),
            WebSecurityConfig.builder().basicAuth("sdk", "test-only").maxRequestBytes(1024).build());
        server.start();
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var uri = java.net.URI.create("http://127.0.0.1:" + server.boundPort());
            var unauthenticated = java.net.http.HttpRequest.newBuilder(uri.resolve("/api/discovery-domains")).GET().build();
            assertEquals(401, client.send(unauthenticated, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
            var catalog = http(client, uri, "/api/discovery-domains", "GET", "");
            assertEquals(200, catalog.statusCode());
            assertTrue(catalog.body().contains("\"domains\":[]"));
            assertEquals(200, http(client, uri, "/static/discovery-domains.html", "GET", "").statusCode());
            assertEquals(405, http(client, uri, "/api/discovery-domains/run", "GET", "").statusCode());
            assertEquals(404, http(client, uri, "/api/discovery-domains/unknown", "POST", "{}").statusCode());
            assertEquals(400, http(client, uri, "/api/discovery-domains/run", "POST", "{").statusCode());
            assertEquals(400, http(client, uri, "/api/discovery-domains/run", "POST", "{\"providerClass\":\"remote.Untrusted\"}").statusCode());
            assertEquals(413, http(client, uri, "/api/discovery-domains/run", "POST",
                "{\"seed\":\"" + "x".repeat(1024) + "\"}").statusCode());
        } finally { server.stop(); }
    }

    private static java.net.http.HttpResponse<String> http(java.net.http.HttpClient client,
            java.net.URI base, String path, String method, String body) throws Exception {
        String credentials = java.util.Base64.getEncoder().encodeToString(
            "sdk:test-only".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var request = java.net.http.HttpRequest.newBuilder(base.resolve(path))
            .header("Authorization", "Basic " + credentials).header("Content-Type", "application/json")
            .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    }

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
