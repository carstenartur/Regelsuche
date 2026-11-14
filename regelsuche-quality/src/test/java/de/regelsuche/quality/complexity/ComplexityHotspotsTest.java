package de.regelsuche.quality.complexity;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

class ComplexityHotspotsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DATE = LocalDate.of(2026, 11, 14);
    @TempDir Path directory;

    @Test
    @EnabledIfSystemProperty(named = "regelsuche.complexityResult", matches = ".+")
    void evaluatesCheckoutPolicyAgainstExtractorOutput() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot", "..")).toAbsolutePath().normalize();
        Path input = root.resolve(System.getProperty("regelsuche.complexityResult"));
        Path output = root.resolve("regelsuche-quality/target/reports/quality/complexity");
        ComplexityHotspotReportMain.main(new String[] {
            input.toString(), root.resolve("config/quality/complexity-hotspots.json").toString(), output.toString()
        });
        var report = JSON.readTree(output.resolve("complexity-hotspot-report.json").toFile());
        assertEquals("PASSED", report.get("status").textValue());
        assertTrue(report.get("currentHotspotCount").intValue() > 0);
    }

    @Test
    void matchesRetainedDecisionForEveryMethod() throws Exception {
        Path root = Path.of(System.getProperty("regelsuche.repositoryRoot", ".."));
        JsonNode expected = JSON.readTree(root.resolve("ai-knowledge/baseline-history/2026-10-11-maind7ec8b51/complexity-hotspot-report.json").toFile());
        ObjectNode raw = JSON.createObjectNode();
        var complexity = raw.putObject("codeComplexity");
        var cognitive = complexity.putArray("topCognitiveMethods");
        var cyclomatic = complexity.putArray("topCyclomaticMethods");
        ObjectNode policy = policy();
        var baseline = policy.withArray("baselineHotspots");
        baseline.removeAll();
        for (var change : expected.get("changes")) {
            cognitive.add(change.get("current"));
            cyclomatic.add(change.get("current"));
            if (!change.get("baseline").isNull()) baseline.add(change.get("baseline"));
        }
        expected.get("retiredOrNoLongerTopHotspots").forEach(baseline::add);
        var report = ComplexityHotspots.evaluate(raw, policy, DATE);
        assertEquals(expected, JSON.valueToTree(report));
    }

    @Test
    void enforcesBothLimitsAndIncludesAnUnqualifiedViolationInBothReports() throws Exception {
        ObjectNode policy = policy();
        var measurement = measurement();
        var method = (ObjectNode) measurement.at("/codeComplexity/topCognitiveMethods/0");
        method.put("cognitiveComplexity", 12).put("cyclomaticComplexity", 11);
        assertEquals("PASSED", ComplexityHotspots.evaluate(measurement, policy, DATE).status());
        method.put("cyclomaticComplexity", 12);
        Path raw = directory.resolve("raw.json");
        Path limits = directory.resolve("policy.json");
        JSON.writeValue(raw.toFile(), measurement);
        JSON.writeValue(limits.toFile(), policy);
        assertThrows(IllegalStateException.class, () -> ComplexityHotspotReportMain.main(new String[] {
            raw.toString(), limits.toString(), directory.toString()
        }));
        var report = JSON.readTree(directory.resolve("complexity-hotspot-report.json").toFile());
        assertEquals("FAILED", report.path("status").asText());
        assertEquals("WORSENED", report.at("/changes/0/status").asText());
        assertEquals(2, report.at("/changes/0/cyclomaticIncrease").intValue());
        var markdown = Files.readString(directory.resolve("complexity-hotspot-report.md"));
        assertTrue(markdown.contains(report.at("/violations/0").asText()));
    }

    @Test
    void newHotspotNeedsAnUnexpiredExactExceptionAndBothCeilings() {
        var raw = measurement();
        var current = (ObjectNode) raw.at("/codeComplexity/topCognitiveMethods/0");
        current.put("signature", "newMethod()");
        var policy = policy();
        var failures = ComplexityHotspots.evaluate(raw, policy, DATE);
        assertEquals("NEW_HOTSPOT", failures.changes().getFirst().status());
        assertEquals(1, failures.retiredOrNoLongerTopHotspots().size());
        var exception = policy.putArray("exceptions").addObject();
        exception.put("id", "debt-1").put("sourceFile", "A.java").put("signature", "newMethod()")
            .put("maximumCognitiveComplexity", 10).put("maximumCyclomaticComplexity", 10)
            .put("rationale", "control").put("expiresOn", DATE.toString());
        assertEquals("PASSED", ComplexityHotspots.evaluate(raw, policy, DATE).status());
        exception.put("maximumCognitiveComplexity", 9);
        assertEquals("FAILED", ComplexityHotspots.evaluate(raw, policy, DATE).status());
        assertThrows(IllegalArgumentException.class, () -> ComplexityHotspots.evaluate(raw, policy, DATE.plusDays(1)));
        policy.withArray("exceptions").add(exception.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ComplexityHotspots.evaluate(raw, policy, DATE));
    }

    @Test
    void rejectsMissingRankingsAndConflictingMeasurements() {
        var raw = measurement();
        var policy = policy();
        raw.withObject("/codeComplexity").withArray("topCyclomaticMethods").removeAll();
        assertThrows(IllegalArgumentException.class, () -> ComplexityHotspots.evaluate(raw, policy, DATE));
        raw.withObject("/codeComplexity").withArray("topCyclomaticMethods").add(method().put("cognitiveComplexity", 9));
        assertThrows(IllegalArgumentException.class, () -> ComplexityHotspots.evaluate(raw, policy, DATE));
        var duplicate = method();
        policy.withArray("baselineHotspots").add(duplicate);
        assertThrows(IllegalArgumentException.class, () -> ComplexityHotspots.evaluate(measurement(), policy, DATE));
    }

    private static ObjectNode policy() {
        var policy = JSON.createObjectNode().put("schema", "regelsuche.quality.complexity-hotspot-policy/v1")
            .put("allowedCognitiveIncrease", 2).put("allowedCyclomaticIncrease", 1);
        policy.putArray("baselineHotspots").add(method());
        return policy;
    }

    private static ObjectNode measurement() {
        var raw = JSON.createObjectNode();
        var code = raw.putObject("codeComplexity");
        var method = method();
        code.putArray("topCyclomaticMethods").add(method);
        code.putArray("topCognitiveMethods").add(method);
        return raw;
    }

    private static ObjectNode method() {
        return JSON.createObjectNode().put("sourceFile", "A.java").put("signature", "method()").put("type", "A")
            .put("cognitiveComplexity", 10).put("cyclomaticComplexity", 10).put("maxNestingDepth", 2);
    }
}
