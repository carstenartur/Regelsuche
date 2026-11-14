package de.regelsuche.experiments.autopilot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiningFanInComparisonTest {
    @Test
    void replaysTheSameTwoFormationBatchesWithAndWithoutSharedSupport(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("formation.json");
        AutonomousProductionCampaignMain.main(new String[]{"--compare-formation-fan-in", file.toString()});
        byte[] replay = Files.readAllBytes(file);
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("docs/evidence"))) root = root.getParent();
        try (var stream = new GZIPInputStream(Files.newInputStream(root.resolve("docs/evidence/open-target-fan-in-v1.json.gz")))) {
            assertArrayEquals(stream.readAllBytes(), replay);
        }
        var report = new JsonMapper().readTree(replay);
        assertFalse(report.required("generation").required("targetProvided").booleanValue());
        var full = report.required("production");
        var isolated = report.required("noFanIn");
        assertEquals(2, full.size());
        assertEquals(2, isolated.size());
        assertEquals(1, full.at("/0/receipt/outputs").size());
        assertEquals(0, full.at("/1/receipt/outputs").size());
        for (int index = 0; index < 2; index++) {
            var expected = full.get(index);
            var actual = isolated.get(index);
            int observations = index == 0 ? 12 : 2;
            assertEquals(expected.required("decision"), actual.required("decision"));
            assertEquals(observations, actual.required("decision").required("inputs").size());
            assertEquals(0, actual.at("/receipt/outputs").size());
            var rejected = actual.at("/mining/rejectedClusters");
            assertEquals(observations, rejected.size());
            for (var cluster : rejected) {
                assertEquals(1, cluster.required("observationIds").size());
                assertEquals("support-count<2", cluster.required("reason").textValue());
            }
        }
    }
}
