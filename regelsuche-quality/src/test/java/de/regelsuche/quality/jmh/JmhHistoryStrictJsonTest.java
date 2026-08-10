package de.regelsuche.quality.jmh;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmhHistoryStrictJsonTest {

    @Test
    void duplicatePolicyKeysAreRejected(@TempDir Path temporary)
            throws Exception {
        Path quality = temporary.resolve("config/quality");
        Files.createDirectories(quality);
        Path policy = quality.resolve("jmh-history-policy.json");
        Files.writeString(policy, """
            {
              "schema":"regelsuche.quality.jmh-history-policy/v1",
              "schema":"regelsuche.quality.jmh-history-policy/v1",
              "normalizedUnit":"ms/op",
              "lowerIsBetter":true,
              "claimBoundary":"duplicate-key fixture",
              "snapshots":[]
            }
            """);
        Path regression = quality.resolve("jmh-regression-policy-v2.json");
        Files.writeString(regression, "{}");

        JmhHistoryLoader.HistoryException failure = assertThrows(
            JmhHistoryLoader.HistoryException.class,
            () -> new JmhHistoryLoader().load(policy, regression)
        );
        assertTrue(failure.getMessage().contains("strict JSON"));
    }
}
