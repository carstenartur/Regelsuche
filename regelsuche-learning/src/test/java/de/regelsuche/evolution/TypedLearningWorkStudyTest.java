package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.search.moves.MoveSearch;
import de.regelsuche.search.moves.SearchMove;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TypedLearningWorkStudyTest {
    private static TypedLearningWorkStudy.Report report;
    @BeforeAll static void runActualStudy() { report = TypedLearningWorkStudy.run(); }

    @Test void comparesEveryConfigurationUnderMatchedBudgetsWithRealLearnedReplay() {
        assertEquals(120, report.rows().size());
        assertEquals(120, report.rows().stream().map(row -> row.caseId() + ":" + row.configuration() + ":" + row.budget()).distinct().count());
        assertEquals(Set.of(50L, 150L, 500L, 2000L), report.rows().stream().map(TypedLearningWorkStudy.Row::budget).collect(Collectors.toSet()));
        assertTrue(report.rows().stream().anyMatch(row -> row.result().outcome() == MoveSearch.Outcome.WORK_EXHAUSTED));
        for (var row : report.rows()) {
            if (!row.result().reached()) continue;
            assertTrue(row.result().metrics().totalWork() <= row.budget());
            assertTrue(row.result().witness().stream().allMatch(step -> step.verification().accepted() && !step.verification().receipts().isEmpty()));
        }
        var primitive = row("one-site", TypedLearningWorkStudy.Configuration.PRIMITIVE_INVENTORY, 50);
        var learned = row("one-site", TypedLearningWorkStudy.Configuration.LEARNED_INVENTORY, 50);
        assertFalse(primitive.result().reached());
        assertTrue(learned.result().reached());
        assertTrue(learned.result().witness().stream().anyMatch(step -> step.move().sourceKind() == SearchMove.SourceKind.LEARNED));
        assertEquals(3, learned.result().metrics().firstHitPrimitiveDepth());
        assertTrue(report.rows().stream().filter(row -> row.caseId().equals("near-miss")).noneMatch(row -> row.result().reached()));
    }

    @Test void recordsTrainingAndUnsuccessfulRowsInReproducibleArtifacts(@TempDir Path output) throws Exception {
        assertTrue(report.formationWork() > 0);
        assertTrue(report.primitive().historySearchWork() > 0);
        assertTrue(report.learned().memoryWork() > 0);
        assertTrue(report.primitive().policy().trainingWork() > 0);
        assertTrue(report.learned().policy().trainingWork() > 0);
        assertEquals(4, report.primitive().historyRuns().size());
        assertEquals(4, report.learned().historyRuns().size());
        assertEquals(3, report.primitive().policy().trials().size());
        assertEquals(3, report.learned().policy().trials().size());
        var directory = TypedLearningWorkStudy.write(report, output);
        var json = new ObjectMapper();
        var summary = json.readTree(Files.readString(directory.resolve("summary.json")));
        assertEquals(120, summary.path("rows").size());
        assertTrue(summary.path("formationWork").asLong() > 0);
        var failed = json.readTree(Files.readString(directory.resolve("run-one-site-PRIMITIVE_INVENTORY-50.json")));
        assertEquals("WORK_EXHAUSTED", failed.path("outcome").asText());
        var successful = json.readTree(Files.readString(directory.resolve("run-one-site-LEARNED_INVENTORY-50.json")));
        assertEquals("TARGET_REACHED", successful.path("outcome").asText());
        assertFalse(successful.path("witness").isEmpty());
        assertFalse(successful.path("events").isEmpty());
        assertEquals(directory, TypedLearningWorkStudy.write(report, output));
        assertEquals(report.modelJson(), Files.readString(directory.resolve("model.json")));
    }

    private static TypedLearningWorkStudy.Row row(String id, TypedLearningWorkStudy.Configuration configuration, long budget) {
        return report.rows().stream().filter(row -> row.caseId().equals(id) && row.configuration() == configuration
            && row.budget() == budget).findFirst().orElseThrow();
    }
}
