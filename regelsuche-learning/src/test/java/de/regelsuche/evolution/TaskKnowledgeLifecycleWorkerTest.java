package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskKnowledgeLifecycleWorkerTest {
    private static Map<String, Object> send(TaskKnowledgeLifecycleWorker worker, Map<String, ?> request) {
        return worker.handle(LearnedSchedulingArtifacts.json(request));
    }
    private static Map<String, Object> task(String source, int nodes) { return Map.of("source", source, "maximumNodes", nodes); }

    @Test void primitiveQueriesAreSourceBoundAndTargetInjectionPoisonsSession() {
        try (var worker = new TaskKnowledgeLifecycleWorker()) {
            assertEquals("INITIALIZED", send(worker, Map.of("op", "initialize", "profile", "PRIMITIVE")).get("status"));
            var response = send(worker, Map.of("op", "run", "task", task("(a+b)*(a-b)+b*b", 3)));
            assertEquals("RESULT", response.get("status")); assertEquals(true, response.get("solved"));
            assertEquals(task("(a+b)*(a-b)+b*b", 3), response.get("task"));
            assertEquals("ERROR", send(worker, Map.of("op", "run", "task", Map.of("source", "x", "maximumNodes", 1, "target", "x"))).get("status"));
            assertEquals("ERROR", send(worker, Map.of("op", "run", "task", task("x", 1))).get("status"));
        }
    }

    @Test void preparedConsumerActuallyUsesStrictLoadThenTheSessionCache(@TempDir Path directory) throws Exception {
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        Path snapshot = knowledge.save(directory);
        try (var worker = new TaskKnowledgeLifecycleWorker()) {
            assertEquals("CONFIGURED", send(worker, Map.of("op", "restore", "profile", "PREPARED_REUSE", "snapshot", snapshot.toString(),
                "directory", directory.toString(), "improvementBatches", List.of(), "futureQueries", 8)).get("status"));
            var first = send(worker, Map.of("op", "load"));
            var again = send(worker, Map.of("op", "load"));
            assertEquals("LOADED", first.get("status")); assertEquals(false, first.get("cacheHit"));
            assertEquals(true, again.get("cacheHit"));
            assertEquals(knowledge.id(), again.get("generationId"));
            assertEquals(0L, again.get("preparationWork"));
        }
    }

    @Test void namedMixedWorkloadIsExplicitAndRejectsSuppliedSpecialistOverrides(@TempDir Path directory) {
        var batch = Map.of("lessons", List.of(task("x+13", 1), task("y+17", 1)),
            "validation", List.of(task("z+19", 1)), "promotion", List.of());
        try (var worker = new TaskKnowledgeLifecycleWorker()) {
            var response = send(worker, Map.of("op", "restore", "profile", "MEASURED_IMPROVEMENT",
                "snapshot", directory.resolve("unused.json").toString(), "directory", directory.toString(),
                "improvementBatches", List.of(batch), "futureQueries", 128,
                "promotionWorkload", MixedTaskKnowledgeWorkload.REVISION));
            assertEquals("CONFIGURED", response.get("status"));
            assertEquals(MixedTaskKnowledgeWorkload.REVISION, response.get("promotionWorkload"));
            assertEquals(MixedTaskKnowledgeWorkload.promotion(), response.get("promotionCases"));
        }
        var specialist = Map.of("lessons", batch.get("lessons"), "validation", batch.get("validation"),
            "promotion", List.of(task("q+23", 1)));
        try (var worker = new TaskKnowledgeLifecycleWorker()) {
            assertEquals("ERROR", send(worker, Map.of("op", "restore", "profile", "MEASURED_IMPROVEMENT",
                "snapshot", "unused.json", "directory", directory.toString(), "improvementBatches", List.of(specialist),
                "futureQueries", 128, "promotionWorkload", MixedTaskKnowledgeWorkload.REVISION)).get("status"));
        }
    }

    @Test void producerCannotBeUsedAsTheFreshQueryConsumer(@TempDir Path directory) {
        var batch = Map.of("lessons", List.of(task("x+13", 1), task("y+17", 1)), "validation", List.of(task("z+19", 1)));
        try (var worker = new TaskKnowledgeLifecycleWorker()) {
            var saved = send(worker, Map.of("op", "acquire", "profile", "LEGACY_SESSION", "seedBlocks", List.of(batch), "directory", directory.toString()));
            assertEquals("SAVED", saved.get("status"));
            assertEquals("ERROR", send(worker, Map.of("op", "run", "task", task("x", 1))).get("status"));
        }
    }

    @Test void measuredAttemptsTravelAsJsonReceiptsWithExplicitActiveIdentity(@TempDir Path directory) throws Exception {
        var knowledge = CumulativeTaskKnowledge.empty(TraceStrategyTransferExample.inventory());
        Path snapshot = knowledge.save(directory);
        var batch = Map.of("lessons", List.of(task("x+13", 1), task("y+17", 1)),
            "validation", List.of(task("z+19", 1)), "promotion", List.of(task("q+23", 1)));
        try (var worker = new TaskKnowledgeLifecycleWorker()) {
            send(worker, Map.of("op", "restore", "profile", "MEASURED_IMPROVEMENT", "snapshot", snapshot.toString(),
                "directory", directory.toString(), "improvementBatches", List.of(batch), "futureQueries", 8));
            var loaded = send(worker, Map.of("op", "load"));
            var wire = CheckedSchemaSupport.read(LearnedSchedulingArtifacts.json(loaded));
            assertEquals("LOADED", wire.path("status").asText());
            assertEquals(knowledge.id(), wire.path("improvement").path("activeId").asText());
            assertTrue(wire.path("improvement").path("knownWork").longValue() > 0);
            assertEquals(1, wire.path("improvement").path("attempts").size());
            assertFalse(wire.path("improvement").has("active"));
        }
    }
}
