package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackFQualificationComparisonTest {
    private static final Path TRACK_F = Path.of(System.getProperty("regelsuche.repositoryRoot", ".."),
        "research", "benchmarks",
        "track-f-stage-allocation");

    @Test
    void reproducesQualificationOfTheRuleFromAllCompletedLineages() throws Exception {
        Path allocation = TRACK_F.resolve("measured.json.gz");
        String generated = TrackFQualificationComparison.run(allocation);
        assertEquals(generated, TrackFQualificationComparison.run(allocation));
        try (var input = new GZIPInputStream(
                Files.newInputStream(TRACK_F.resolve("qualification.json.gz")))) {
            assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8), generated);
        }

        var report = new ObjectMapper().readTree(generated);
        assertEquals(1, report.required("qualificationExecutions").asInt());
        var evidence = report.required("qualification").required("evidence");
        assertTrue(evidence.required("qualified").asBoolean());
        assertEquals(12, evidence.required("executedPositiveHoldouts").asInt());
        assertEquals(12, evidence.required("executedNegativeHoldouts").asInt());
        assertEquals(0, evidence.required("correctnessRegressionCount").asInt());
        var qualifiedByLane = new ArrayList<Integer>();
        var allLineages = new ArrayList<String>();
        for (var lane : report.required("lanes")) {
            qualifiedByLane.add(lane.required("qualifiedLineages").asInt());
            lane.required("lineages").forEach(lineage ->
                allLineages.add(lineage.required("qualificationStatus").asText()));
        }
        assertEquals(List.of(1, 0, 1, 2, 1, 2), qualifiedByLane);
        assertEquals(9, allLineages.size());
        assertEquals(2, allLineages.stream().filter("NOT_EVALUATED"::equals).count());
    }

    @Test
    void rejectsAnUnregisteredStageAllocationBeforeRunningQualification(@TempDir Path temp)
            throws Exception {
        Path input = temp.resolve("different-allocation.json");
        Files.writeString(input, "{\"schema\":\"regelsuche.track-f-stage-allocation/v1\"}");
        var error = assertThrows(IllegalArgumentException.class,
            () -> TrackFQualificationComparison.run(input));
        assertTrue(error.getMessage().contains("frozen Track F allocation"));
    }
}
