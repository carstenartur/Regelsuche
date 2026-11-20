package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class JmhWarmupMigrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root = Path.of(System.getProperty("regelsuche.repositoryRoot"));
    private final Path policies = root.resolve("config/quality");

    @Test
    void currentPoliciesRetainEveryOriginalCeilingReferenceAndDecisionRule() throws Exception {
        var previous = policy("jmh-regression-policy-v2");
        var current = policy("jmh-regression-policy-more-warmup-v1");
        for (String field : List.of("benchmarks", "baselineRevision", "baselineArtifactDigest")) {
            assertEquals(previous.required(field), current.required(field), field);
        }
        assertEquals(previous.required("execution"), current.required("baselineExecution"));
        var execution = previous.withObject("execution").deepCopy();
        assertEquals(2, execution.required("warmupIterations").intValue());
        execution.put("warmupIterations", 6).put("warmupTime", "1s").put("measurementTime", "1s");
        assertEquals(execution, current.required("execution"));
        var oldDecision = policy("jmh-regression-decision-policy-v3");
        var decision = policy("jmh-regression-decision-policy-more-warmup-v1");
        for (String field : List.of("schema", "thresholdPolicySchema", "decisionStatistic", "failureCondition",
                "inconclusiveCondition", "boundaryPolicy", "lowPrecisionDiagnostic")) {
            assertEquals(oldDecision.required(field), decision.required(field), field);
        }
        Path threshold = Path.of("config/quality/jmh-regression-policy-more-warmup-v1.json");
        assertEquals(threshold.toString().replace('\\', '/'), decision.required("thresholdPolicyPath").textValue());
        byte[] bytes = Files.readAllBytes(root.resolve(threshold));
        var blob = MessageDigest.getInstance("SHA-1");
        blob.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        assertEquals(HexFormat.of().formatHex(blob.digest(bytes)), decision.required("thresholdPolicyGitBlobSha1").textValue());
        var oldPublication = policy("jmh-baseline");
        var publication = policy("jmh-baseline-more-warmup-v1");
        for (String field : List.of("benchmarks", "baselineRevision")) {
            assertEquals(oldPublication.required(field), publication.required(field), field);
        }
        assertEquals(oldPublication.required("measurementPolicy"), publication.required("baselineMeasurementPolicy"));
        assertEquals(oldPublication.withObject("measurementPolicy").deepCopy().put("warmupIterations", 6), publication.required("measurementPolicy"));
    }

    @Test
    void activeAndHistoricalContentBoundPoliciesAreDeclaredLfOnEveryCheckout() throws Exception {
        var attributes = Files.readAllLines(root.resolve(".gitattributes"));
        for (String name : List.of("jmh-regression-policy-v2", "jmh-regression-decision-policy-v3",
                "jmh-regression-policy-more-warmup-v1", "jmh-regression-decision-policy-more-warmup-v1", "jmh-baseline-more-warmup-v1")) {
            assertTrue(attributes.contains("/config/quality/" + name + ".json text eol=lf"), name);
            assertFalse(Files.readString(policies.resolve(name + ".json")).contains("\r"), name);
        }
    }

    private JsonNode policy(String name) throws Exception {
        return JSON.readTree(policies.resolve(name + ".json").toFile());
    }
}
