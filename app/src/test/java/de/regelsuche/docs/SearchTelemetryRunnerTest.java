package de.regelsuche.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchTelemetryRunnerTest {

    @Test
    void writesTelemetryArtifactsForConfiguredScenario(@TempDir Path tempDir) throws Exception {
        SearchTelemetryRunner.TelemetryReport report = new SearchTelemetryRunner().writeReport(tempDir);

        assertEquals("search-telemetry", report.id());
        assertEquals(1, report.results().size());

        Path scenarioDir = tempDir.resolve("complete-square-factorization");
        assertTrue(Files.exists(scenarioDir.resolve("search-events.ndjson")));
        assertTrue(Files.exists(scenarioDir.resolve("search-telemetry-summary.json")));
        assertTrue(Files.exists(scenarioDir.resolve("search-telemetry-summary.md")));
        assertTrue(Files.exists(scenarioDir.resolve("search-telemetry-replay.html")));
        assertTrue(Files.exists(scenarioDir.resolve("search-telemetry-timeline.svg")));
        assertTrue(Files.exists(tempDir.resolve("search-telemetry-index.json")));
        assertTrue(Files.exists(tempDir.resolve("search-telemetry-summary.md")));

        String ndjson = Files.readString(scenarioDir.resolve("search-events.ndjson"), StandardCharsets.UTF_8);
        assertTrue(ndjson.contains("\"type\":\"SEARCH_STARTED\""));
        assertTrue(ndjson.contains("\"type\":\"SEARCH_FINISHED\""));

        String svg = Files.readString(scenarioDir.resolve("search-telemetry-timeline.svg"), StandardCharsets.UTF_8);
        assertTrue(svg.contains("<svg"));
        assertTrue(svg.contains("data-generated-by=\"TelemetryTimelineSvgWriter\""));
    }
}
