package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.regelsuche.discovery.representation.*;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import de.regelsuche.search.SearchHeuristic;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TargetFreeSearchWorkspaceHttpTest {
    @TempDir Path temporary;

    @Test void realTargetFreeRunReopensThroughTheExistingWorkspaceAndDossierEndpoints() throws Exception {
        Path runs = temporary.resolve("runs");
        var run = TargetFreeRepresentationDiscoveryRun.writeTargetFree(runs, "(x + 0) * (x + 0)",
            new SearchHeuristic(3, 12, 1, 1, 8, 8), "0123456789abcdef0123456789abcdef01234567");
        String previous = System.getProperty("regelsuche.discovery.runs.directory");
        System.setProperty("regelsuche.discovery.runs.directory", runs.toString());
        var server = new WebWorkbenchServer("127.0.0.1", 0, new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(), new DefaultTransformationExportService(), WebSecurityConfig.none());
        try (var client = HttpClient.newHttpClient()) {
            server.start();
            String base = "http://127.0.0.1:" + server.boundPort() + "/api/discovery-runs/" + run.workspace().runId().substring(7);
            var manifest = client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, manifest.statusCode(), manifest.body());
            assertEquals(run.workspace().toCanonicalJson(), manifest.body());
            var dossier = client.send(HttpRequest.newBuilder(URI.create(base + "/dossier")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, dossier.statusCode(), dossier.body());
            assertEquals(run.workspace().runId(), dossier.headers().firstValue("X-Regelsuche-Run-Id").orElseThrow());
            assertEquals(run.artifact().toCanonicalJson(), dossier.body());
            var json = new JsonMapper();
            var content = json.readTree(dossier.body()).path("content");
            assertTrue(content.path("generations").size() >= content.path("transitions").size());
            assertTrue(content.path("states").size() > 1);
            var imported = client.send(HttpRequest.newBuilder(URI.create(base + "/dossier")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(dossier.body())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, imported.statusCode(), imported.body());
            Path persisted = runs.resolveSibling("runs-dossiers").resolve(run.workspace().runId().substring(7) + ".json");
            Files.writeString(persisted, dossier.body().replace("UNTARGETED", "REACHED"));
            var tampered = client.send(HttpRequest.newBuilder(URI.create(base + "/dossier")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, tampered.statusCode(), tampered.body());
        } finally {
            server.stop();
            if (previous == null) System.clearProperty("regelsuche.discovery.runs.directory");
            else System.setProperty("regelsuche.discovery.runs.directory", previous);
        }
    }
}
