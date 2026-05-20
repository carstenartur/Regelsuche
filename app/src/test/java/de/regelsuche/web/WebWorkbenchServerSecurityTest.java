package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WebWorkbenchServerSecurityTest {
    private WebWorkbenchServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void basicAuthIsEnforcedOnApiEndpoints() throws IOException, InterruptedException {
        WebSecurityConfig config = WebSecurityConfig.builder()
            .basicAuth("admin", "s3cret")
            .realm("Regelsuche-Test")
            .build();
        server = new WebWorkbenchServer(
            "127.0.0.1",
            0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService(),
            config
        );
        server.start();
        int port = server.boundPort();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> unauthorized = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inventory")).GET().build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(401, unauthorized.statusCode());

        String creds = Base64.getEncoder().encodeToString("admin:s3cret".getBytes());
        HttpResponse<String> authorized = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inventory"))
                .header("Authorization", "Basic " + creds)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, authorized.statusCode());

        String wrong = Base64.getEncoder().encodeToString("admin:wrong".getBytes());
        HttpResponse<String> badPassword = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/inventory"))
                .header("Authorization", "Basic " + wrong)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(401, badPassword.statusCode());
    }
}
