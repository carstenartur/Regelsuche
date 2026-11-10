package de.regelsuche.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.InMemoryRuleInventoryRepository;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code GET /api/benchmark} surfaces the quality metrics
 * introduced in PR #16 follow-up §2 so the workbench dashboard can render
 * "Ampelstatus", "gefunden / erwartet getroffen / Proof-Status", e-graph size
 * and explicitly unavailable savings without inferring an unmeasured ratio.
 */
class BenchmarkApiQualityMetricsTest {

    private WebWorkbenchServer server;

    @BeforeEach
    void start() throws IOException {
        server = new WebWorkbenchServer(
            "127.0.0.1", 0,
            new InMemoryExpressionGraphStore(),
            new InMemoryRuleInventoryRepository(),
            new DefaultTransformationExportService()
        );
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void benchmarkDashboardShowsExpectedResultMatched() throws IOException {
        HttpURLConnection connection = open("/api/benchmark");
        assertEquals(200, connection.getResponseCode());
        String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // All quality fields documented in the PR must appear in the payload.
        assertTrue(body.contains("\"expectedResultMatched\""),
            "benchmark JSON must include expectedResultMatched");
        assertTrue(body.contains("\"visitedStates\""),
            "benchmark JSON must include visitedStates");
        assertTrue(body.contains("\"prunedStates\""),
            "benchmark JSON must include prunedStates");
        assertTrue(body.contains("\"eGraphClasses\"") && body.contains("\"eGraphNodes\""),
            "benchmark JSON must include e-graph size");
        assertTrue(body.contains("\"classesScanned\"") && body.contains("\"nodesScanned\""),
            "benchmark JSON must include matcher scan metrics");
        assertTrue(body.contains("\"candidateClassesSkipped\"") && body.contains("\"matchesFound\""),
            "benchmark JSON must include matcher candidate/match metrics");
        assertTrue(body.contains("\"matcherCacheHits\"") && body.contains("\"matcherCacheMisses\""),
            "benchmark JSON must include matcher cache metrics");
        assertTrue(body.contains("\"saturationIterations\"") && body.contains("\"rulesFired\""),
            "benchmark JSON must include saturation iteration/rule metrics");
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        for (var scenario : payload.get("scenarios")) {
            for (var row : scenario.get("results")) {
                assertTrue(row.get("saturationSavings").isNull(), "no savings baseline was measured");
                assertEquals("UNAVAILABLE", row.get("saturationSavingsStatus").asText());
                assertTrue(!row.get("saturationSavingsReason").asText().isBlank());
            }
        }
        assertTrue(!body.contains("NaN"), "compatibility sentinel must not escape into JSON");
        assertTrue(body.contains("\"learnedRuleUsed\""),
            "benchmark JSON must include learnedRuleUsed");
        assertTrue(body.contains("\"exportBundleValid\""),
            "benchmark JSON must include exportBundleValid");
        assertTrue(body.contains("\"quality\""),
            "benchmark JSON must include the OK/WARN/FAIL quality label");
    }

    private HttpURLConnection open(String path) throws IOException {
        URI uri = URI.create("http://127.0.0.1:" + server.boundPort() + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("GET");
        return connection;
    }
}
