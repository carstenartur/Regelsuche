package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmhRegressionV3VerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temporary;

    @Test
    void replaysTheCompleteFrozenInventoryUsingTheRealBoundV2Policy()
            throws Exception {
        Fixture fixture = fixture();
        JmhRegressionV3Verifier.Result report = check(fixture);
        assertEquals("PASSED", report.status());
        assertEquals(29, report.json().path("benchmarkCount").intValue());
        assertEquals(0, report.json().path("missingBenchmarks").size());
        assertEquals(0, report.json().path("unexpectedBenchmarks").size());
        assertEquals(0, report.json().path("violations").size());
        assertEquals(
            "ca2b92d4082224f1cc46d9567f609f5e1e427898",
            report.json().path("thresholdPolicyGitBlobSha1").asText()
        );
        assertTrue(report.markdown().contains(
            "The frozen v2 benchmark inventory and finite thresholds "
                + "remain unchanged and content-bound."
        ));
        Path json = temporary.resolve("reports/gate.json");
        Path markdown = temporary.resolve("reports/gate.md");
        report.write(json, markdown);
        assertEquals("PASSED", JSON.readTree(json.toFile())
            .path("status").asText());
        assertEquals(report.markdown(), Files.readString(markdown));
    }

    @Test
    void separatesPointEstimateAndUncertaintyWithoutWeakeningTheRatchet()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode first = (ObjectNode) fixture.rows().get(0);
        JsonNode policy = fixture.thresholds().path("benchmarks").get(0);
        double maximum = policy.path("maximumAllowedScore").doubleValue();

        ObjectNode metric = (ObjectNode) first.path("primaryMetric");
        metric.put("score", maximum + 0.2);
        metric.put("scoreError", 0.3);
        assertEquals("INCONCLUSIVE", check(fixture).status());
        assertEquals(1, check(fixture).json().path("inconclusiveCount").intValue());
        assertEquals("INCONCLUSIVE",
            check(fixture).json().path("benchmarks").get(0)
                .path("status").asText());

        metric.put("scoreError", 0.0);
        JmhRegressionV3Verifier.Result failure = check(fixture);
        assertEquals("FAILED", failure.status());
        assertTrue(failure.json().path("violations").get(0)
            .asText().contains("decision score"));

        metric.put("score", maximum);
        assertEquals("PASSED", check(fixture).status(),
            "equality with the frozen bound must pass");
    }

    @Test
    void keepsLowPrecisionBelowThresholdVisibleButDoesNotCreateAPassAboveIt()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode first = (ObjectNode) fixture.rows().get(0);
        ObjectNode metric = (ObjectNode) first.path("primaryMetric");
        double baseline = fixture.thresholds().path("benchmarks").get(0)
            .path("baselineScore").doubleValue();

        metric.put("score", baseline);
        metric.put("scoreError", baseline * 2);
        JmhRegressionV3Verifier.Result low = check(fixture);
        assertEquals("PASSED", low.status());
        assertEquals(1, low.json().path("lowPrecisionCount").intValue());
        assertEquals("LOW_PRECISION", low.json().path("benchmarks").get(0)
            .path("precisionStatus").asText());

        double maximum = fixture.thresholds().path("benchmarks").get(0)
            .path("maximumAllowedScore").doubleValue();
        metric.put("score", maximum + 0.5);
        metric.put("scoreError", maximum + 0.5);
        assertEquals("INCONCLUSIVE", check(fixture).status());
    }

    @Test
    void refusesInventoriesExecutionDriftAndMalformedEvidence()
            throws Exception {
        Fixture fixture = fixture();
        ((ObjectNode) fixture.rows().get(0)).put("jdkVersion", "21.0.1");
        assertEquals("FAILED", check(fixture).status());

        ((ObjectNode) fixture.rows().get(0)).put("jdkVersion", "25.0.1");
        fixture.rows().remove(0);
        JmhRegressionV3Verifier.Result missing = check(fixture);
        assertEquals("FAILED", missing.status());
        assertEquals(1, missing.json().path("missingBenchmarks").size());

        ObjectNode unexpected = fixture.rows().addObject();
        unexpected.put("benchmark", "invented.unregistered");
        assertEquals("FAILED", check(fixture).status());

        fixture.rows().remove(fixture.rows().size() - 1);
        fixture.rows().add(fixture.rows().get(0).deepCopy());
        IllegalArgumentException duplicate = assertThrows(
            IllegalArgumentException.class, () -> check(fixture));
        assertTrue(duplicate.getMessage().contains("duplicate result"));
    }

    @Test
    void rejectsNonfiniteValuesPolicySubstitutionAndDuplicateJsonKeys()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode first = (ObjectNode) fixture.rows().get(0);
        ObjectNode metric = (ObjectNode) first.path("primaryMetric");
        metric.put("score", -1);
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> check(fixture)).getMessage().contains("finite"));

        metric.put("score", 1);
        metric.put("scoreError", -1);
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> check(fixture)).getMessage().contains("nonnegative"));

        metric.put("scoreError", 0);
        Path counterfeit = temporary.resolve("counterfeit-v2.json");
        Files.writeString(counterfeit, fixture.thresholds().toPrettyString());
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> new JmhRegressionV3Verifier().verify(
                fixture.root(), fixture.resultPath(),
                counterfeit, fixture.decisionPath()))
            .getMessage().contains("threshold path"));

        Files.writeString(fixture.resultPath(),
            "[{\"benchmark\":\"a\",\"benchmark\":\"b\"}]");
        assertTrue(assertThrows(IllegalArgumentException.class,
            () -> new JmhRegressionV3Verifier().verify(
                fixture.root(), fixture.resultPath(),
                fixture.thresholdPath(), fixture.decisionPath()))
            .getMessage().contains("invalid JSON"));
    }

    @Test
    void reportsMissingUnitsAndRejectsAliasedReportOutputs()
            throws Exception {
        Fixture fixture = fixture();
        ObjectNode metric = (ObjectNode) fixture.rows().get(0)
            .path("primaryMetric");
        metric.remove("scoreUnit");
        assertEquals("FAILED", check(fixture).status());
        metric.put("scoreUnit", fixture.thresholds().path("benchmarks")
            .get(0).path("unit").asText());
        Files.writeString(fixture.resultPath(),
            JSON.writeValueAsString(fixture.rows()));
        IllegalArgumentException alias = assertThrows(
            IllegalArgumentException.class, () -> JmhRegressionV3Main.main(
                new String[] { fixture.root().toString(),
                    fixture.resultPath().toString(),
                    fixture.thresholdPath().toString(),
                    fixture.decisionPath().toString(),
                    fixture.resultPath().toString(),
                    temporary.resolve("report.md").toString() }));
        assertTrue(alias.getMessage().contains("aliases retained source"));
    }

    private JmhRegressionV3Verifier.Result check(Fixture fixture)
            throws Exception {
        Files.writeString(fixture.resultPath(),
            JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(fixture.rows()));
        return new JmhRegressionV3Verifier().verify(
            fixture.root(), fixture.resultPath(),
            fixture.thresholdPath(), fixture.decisionPath());
    }

    private Fixture fixture() throws Exception {
        String property = System.getProperty("regelsuche.repositoryRoot");
        assertFalse(property == null || property.isBlank(),
            "Maven must supply the repository root");
        Path root = Path.of(property).toRealPath();
        Path thresholdsPath = root.resolve(
            "config/quality/jmh-regression-policy-v2.json");
        Path decisionPath = root.resolve(
            "config/quality/jmh-regression-decision-policy-v3.json");
        JsonNode thresholds = JSON.readTree(thresholdsPath.toFile());
        JsonNode execution = thresholds.path("execution");
        ArrayNode rows = JSON.createArrayNode();
        for (JsonNode benchmark : thresholds.path("benchmarks")) {
            ObjectNode row = rows.addObject();
            row.put("benchmark", benchmark.path("benchmark").asText());
            row.set("jmhVersion", execution.path("jmhVersion"));
            row.set("mode", execution.path("mode"));
            row.set("forks", execution.path("forks"));
            row.set("warmupIterations",
                execution.path("warmupIterations"));
            row.set("measurementIterations",
                execution.path("measurementIterations"));
            row.put("jdkVersion", "25.0.1");
            ObjectNode primary = row.putObject("primaryMetric");
            primary.put("score",
                benchmark.path("baselineScore").doubleValue());
            primary.put("scoreError", 0.0);
            primary.put("scoreUnit", benchmark.path("unit").asText());
        }
        return new Fixture(root, thresholdsPath, decisionPath,
            temporary.resolve("jmh.json"), thresholds, rows);
    }

    private record Fixture(
        Path root,
        Path thresholdPath,
        Path decisionPath,
        Path resultPath,
        JsonNode thresholds,
        ArrayNode rows
    ) { }
}
