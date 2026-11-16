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


    /**
     * Source: CI 43806895498, artifact 14199503763 (jmh-verification),
     * sha256:9f81c50e5cb16fe0d6930e5fc9c0a0dd530bfc2810868e2608a96c53cb799e12.
     * The genuine 29 JMH scores and error bars are replayed against the frozen
     * policy metadata. The expected digest is of the actual Python v3 Markdown
     * report in that artifact, not a Java-generated golden file.
     * This numeric parity test does not replace full raw-result/Failsafe replay.
     */
    @Test
    void comparesActualCapturedJmhScoresWithPythonV3Report()
            throws Exception {
        // Compare the captured six-warmup run against the policy that
        // generated the retained Python report, not the two-warmup v3 policy.
        Fixture fixture = fixture(
            "config/quality/jmh-regression-policy-more-warmup-v1.json",
            "config/quality/jmh-regression-decision-policy-more-warmup-v1.json");
        double[][] captured = {
            {1.5995228268335471, 0.3559137335375816},
            {7.1812426172696293, 1.1158911616215206},
            {4523.575835582189, 108.97048701307266},
            {6.3763253955892649, 0.21214541999227948},
            {1743.9356274170134, 91.714121905014864},
            {512.97778592576583, 379.36938359822636},
            {2.1505370342163408, 0.26105016592351049},
            {60.553218779617652, 21.171351926534424},
            {175.34965237909992, 11.764468742213968},
            {1008.4048130328738, 43.683476696792646},
            {19.869532143790849, 1.5620728612856685},
            {0.47962747409635936, 0.015797353422123593},
            {26.552663548582995, 7.0789054053500218},
            {0.63327391414984036, 0.082071500956924573},
            {0.17130763391884654, 0.0027308800067680577},
            {2.7460749784708588e-05, 1.9619701187417114e-06},
            {0.1813689383741606, 0.0070720834396827753},
            {0.0010296486520054232, 0.00021805709787486302},
            {0.00066511638976319805, 0.00016501993741563416},
            {0.0048373425058469179, 2.1239169931469284e-05},
            {0.0011218761104071278, 0.00011053175203673385},
            {0.0010901526835401909, 0.00012754066505100284},
            {0.0030393852223181291, 0.00021903591528957316},
            {0.0011589655648462851, 0.0001183288294392515},
            {0.0013372960794416691, 8.5358844429848831e-05},
            {0.00053777527093593904, 6.4817382638064272e-05},
            {0.11459372293133223, 0.049437886812933422},
            {0.17714661775703353, 0.011385653933399614},
            {0.0007219212237021532, 1.5932753867116548e-05}
        };
        assertEquals(fixture.rows().size(), captured.length);
        for (int i = 0; i < captured.length; i++) {
            ObjectNode metric = (ObjectNode) fixture.rows().get(i)
                .path("primaryMetric");
            metric.put("score", captured[i][0]);
            metric.put("scoreError", captured[i][1]);
            ((ObjectNode) fixture.rows().get(i)).put("jdkVersion", "25.0.5");
        }
        JmhRegressionV3Verifier.Result result = check(fixture);
        assertEquals("188fe77d58c519fe0b5beeaf1cd22ce1c8b1916d",
            result.json().path("thresholdPolicyGitBlobSha1").asText());
        assertEquals("PASSED", result.status());
        assertEquals(29, result.json().path("benchmarkCount").asInt());
        assertEquals(0, result.json().path("lowPrecisionCount").asInt());
        assertEquals(0, result.json().path("inconclusiveCount").asInt());
        byte[] text = result.markdown().getBytes(
            java.nio.charset.StandardCharsets.UTF_8);
        String digest = java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(text));
        assertEquals(
            "dca47799e786234b53470e2b7f5070676b0df6dc8edd829b132f919c2654b0d6",
            digest, "Java Markdown must match the Python report of the genuine JMH numbers");

        // Negative scenarios start from the same captured execution and
        // must distinguish uncertainty from a real regression.
        ObjectNode metric = (ObjectNode) fixture.rows().get(0)
            .path("primaryMetric");
        double limit = fixture.thresholds().path("benchmarks")
            .get(0).path("maximumAllowedScore").asDouble();
        metric.put("score", limit + 0.2);
        metric.put("scoreError", 0.3);
        assertEquals("INCONCLUSIVE", check(fixture).status());
        metric.put("scoreError", 0.0);
        assertEquals("FAILED", check(fixture).status());
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
        return fixture("config/quality/jmh-regression-policy-v2.json",
            "config/quality/jmh-regression-decision-policy-v3.json");
    }

    private Fixture fixture(String thresholdRelativePath, String decisionRelativePath)
            throws Exception {
        String property = System.getProperty("regelsuche.repositoryRoot");
        assertFalse(property == null || property.isBlank(),
            "Maven must supply the repository root");
        Path root = Path.of(property).toRealPath();
        Path thresholdsPath = root.resolve(thresholdRelativePath);
        Path decisionPath = root.resolve(decisionRelativePath);
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
