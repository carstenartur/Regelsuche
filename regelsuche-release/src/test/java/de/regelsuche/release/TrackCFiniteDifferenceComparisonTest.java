package de.regelsuche.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrackCFiniteDifferenceComparisonTest {
    private static final JsonMapper JSON = new JsonMapper();
    @TempDir Path temporary;

    @Test
    void regeneratesAllFrozenAttemptsAndEvaluationsByteForByte() throws Exception {
        Path data = corpus();
        Path result = temporary.resolve("new.json");
        TrackCFiniteDifferenceComparison.main(new String[]{data.toString(), result.toString()});
        assertEquals(Files.readString(data.resolve("track-c-finite-differences/comparison-result.json")),
            Files.readString(result));
        JsonNode report = JSON.readTree(result.toFile());
        assertEquals(12, report.required("campaigns").size());
        for (JsonNode campaign : report.required("campaigns")) {
            assertEquals(6, campaign.required("evaluations").size());
            assertEquals(2, campaign.required("formation").required("inputs").size());
            for (JsonNode formation : campaign.required("formation").required("inputs")) {
                assertTrue(formation.required("attempts").size() <= 30);
                assertFalse(formation.has("holdoutContinuation"));
            }
        }
    }

    private static Path corpus() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path data = directory.resolve("research/benchmarks/candidate-independent");
            if (Files.isDirectory(data)) return data;
            directory = directory.getParent();
        }
        throw new IllegalStateException("frozen corpus is absent from checkout");
    }

    @Test
    void rejectsChangedFrozenCorpusBeforeProducingEvidence() throws Exception {
        Path data = corpus();
        Files.createDirectories(temporary.resolve("track-c-finite-differences"));
        for (String name : new String[]{"case-corpus.json", "corpus-freeze-receipt.json",
                "finite-sequence-candidate-forms.json", "track-c-finite-differences/comparison-plan.json"}) {
            Files.copy(data.resolve(name), temporary.resolve(name));
        }
        ObjectNode corrupt = (ObjectNode) JSON.readTree(temporary.resolve("case-corpus.json").toFile());
        ((ObjectNode) corrupt.required("cases").get(0)).put("caseId", "substitution");
        Files.writeString(temporary.resolve("case-corpus.json"), corrupt.toString());
        Path output = temporary.resolve("result.json");
        assertThrows(IllegalArgumentException.class,
            () -> TrackCFiniteDifferenceComparison.main(new String[]{temporary.toString(), output.toString()}));
        assertFalse(Files.exists(output));
    }
}
