package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Replays original CI output through the production command, including its exit decision. */
class JmhRetainedCiReplayIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path ROOT = Path.of(System.getProperty("regelsuche.repositoryRoot")).toAbsolutePath().normalize();

    @TempDir Path output;

    @Test
    void acceptedSixWarmupMeasurementsReproduceTheEntireOriginalReport() throws Exception {
        replay("44712656489", "PASSED", "more-warmup-v1", "more-warmup-v1",
            "12c1a6966ca61301a2aad2b8c0aceb3b258b253296ba4e3c6dcbcf2d35f90069",
            "6cbda93af2ec2f8851661882ee386366db80bea73d61761df61c095f0ca75ae2");
    }

    @Test
    void inconclusiveTwoWarmupMeasurementsRemainNonpassingUnderTheirOriginalPolicy() throws Exception {
        replay("35417755959", "INCONCLUSIVE", "v2", "v3",
            "a7da6fc3bf32d58adbc35bbedce7fbd00d4b33c07d4f38a5cd85ea2d4dd9a593",
            "1b7ea505c6aad07a5c8957c936cbec6353257e41c9b225d4ecbf3428f737a9e0");
    }

    private void replay(String run, String status, String thresholdVersion, String decisionVersion,
                        String rawHash, String originalHash) throws Exception {
        Path retained = ROOT.resolve("regelsuche-quality/src/test/resources/jmh-ci").resolve(run);
        Path raw = retained.resolve("result.json");
        Path original = retained.resolve("report.json");
        assertEquals(rawHash, sha256(raw));
        assertEquals(originalHash, sha256(original));
        Path threshold = ROOT.resolve("config/quality/jmh-regression-policy-" + thresholdVersion + ".json");
        Path decision = ROOT.resolve("config/quality/jmh-regression-decision-policy-" + decisionVersion + ".json");
        Path result = output.resolve("report.json");
        String[] arguments = {ROOT.toString(), raw.toString(), threshold.toString(), decision.toString(),
            result.toString(), output.resolve("report.md").toString()};
        if ("PASSED".equals(status)) {
            JmhRegressionV3Main.main(arguments);
        } else {
            var rejected = assertThrows(IllegalStateException.class, () -> JmhRegressionV3Main.main(arguments));
            assertTrue(rejected.getMessage().contains(status));
        }
        ObjectNode expected = (ObjectNode) JSON.readTree(original.toFile());
        ObjectNode actual = (ObjectNode) JSON.readTree(result.toFile());
        // Only the absolute checkout prefix differs between CI and this checkout.
        for (String field : new String[]{"decisionPolicy", "thresholdPolicy"}) {
            String current = actual.get(field).asText();
            assertEquals(ROOT.relativize(Path.of(current)).toString().replace('\\', '/'),
                expected.get(field).asText().substring("/home/runner/work/Regelsuche/Regelsuche/".length()));
            expected.put(field, current);
        }
        assertEquals(status, actual.path("status").textValue());
        assertEquals(29, actual.path("benchmarkCount").intValue());
        assertEquals(expected, actual);
        assertEquals(rawHash, sha256(raw));
    }

    private static String sha256(Path path) throws Exception {
        assertTrue(Files.isRegularFile(path), () -> "Missing original CI capture " + path);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
