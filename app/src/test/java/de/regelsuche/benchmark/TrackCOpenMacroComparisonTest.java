package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

class TrackCOpenMacroComparisonTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void reproducesEveryOpenMacroSelectionAndPairedTask(@TempDir Path temp) throws Exception {
        Path data = corpus(), output = temp.resolve("comparison.json");
        TrackCOpenMacroComparison.main(new String[] {data.toString(), output.toString()});
        try (var archive = new GZIPInputStream(Files.newInputStream(
                data.resolve("track-c-open-macros/comparison-result.json.gz")))) {
            assertArrayEquals(archive.readAllBytes(), Files.readAllBytes(output));
        }
        var rows = JSON.readTree(output.toFile()).required("campaigns");
        assertEquals(12, rows.size());
        for (var row : rows) assertEquals(12, row.required("evaluations").size());
    }

    @Test
    void changingOrDeletingDemonstratedAnswersCannotAffectFormationInputs() throws Exception {
        var cases =
                CandidateIndependentReusableMacroBatchMain.macroCases(
                        CandidateIndependentReusableMacroBatchMain.readObject(
                                corpus().resolve("case-corpus.json")));
        var originals = TrackCOpenMacroComparison.trainStarts(cases);
        var mutated = new ArrayList<ObjectNode>();
        for (var row : cases) {
            var changed = row.deepCopy();
            changed.remove("evaluationInput");
            if ("TRAIN".equals(changed.required("split").textValue())) {
                for (var node : changed.required("formationInput").required("replayTraces")) {
                    var trace = (ObjectNode) node;
                    trace.remove(List.of("traceId", "target", "primitiveSteps"));
                }
            }
            mutated.add(changed);
        }
        assertEquals(4, originals.size());
        assertEquals(originals, TrackCOpenMacroComparison.trainStarts(mutated));
        var first =
                (ObjectNode)
                        mutated.getFirst()
                                .required("formationInput")
                                .required("replayTraces")
                                .get(0);
        first.put("source", "new_public_start");
        assertNotEquals(originals, TrackCOpenMacroComparison.trainStarts(mutated));
    }

    private static Path corpus() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            var data = dir.resolve("research/benchmarks/candidate-independent");
            if (Files.isDirectory(data)) return data;
        }
        throw new IllegalStateException("frozen corpus absent");
    }
}
