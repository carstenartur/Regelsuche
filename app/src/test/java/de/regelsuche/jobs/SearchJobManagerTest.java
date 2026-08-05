package de.regelsuche.jobs;

import static de.regelsuche.testsupport.ConditionAwaiter.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.input.InputType;
import de.regelsuche.notify.NoOpNotifier;
import de.regelsuche.search.SearchHeuristic;
import de.regelsuche.search.TransformationSearchService;
import de.regelsuche.transform.AstRewriteTransformationEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchJobManagerTest {

    private SearchJobManager newManager() {
        return new SearchJobManager(job -> new TransformationSearchService(
            new AstRewriteTransformationEngine(),
            new InMemoryExpressionGraphStore(),
            new SearchHeuristic(4, 50, 1),
            new NoOpNotifier()
        ));
    }

    @Test
    void submitRunsJobToCompletion() throws Exception {
        SearchJobManager manager = newManager();
        try {
            SearchJob job = manager.submit(
                "(x + 0) * 1", InputType.TERM, "FAST_SIMPLIFY", List.of());
            assertNotNull(job.id());
            await(
                Duration.ofSeconds(4),
                () -> manager.get(job.id())
                    .map(current -> current.state() == SearchJob.State.DONE
                        || current.state() == SearchJob.State.FAILED)
                    .orElse(false),
                "search job did not reach a terminal state"
            );

            SearchJob result = manager.get(job.id()).orElseThrow();
            assertEquals(SearchJob.State.DONE, result.state(),
                "job did not finish: " + result);
            assertEquals("completed", result.activePhase());
            assertNotNull(result.lastProcessedExpression());
            assertTrue(result.knownStateCount() >= 1);
            assertTrue(result.projectedStateCount() >= result.knownStateCount());
            assertNotNull(result.searchSpaceRisk());
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void checkpointAndRestorePreserveJobMetadata(@TempDir Path tempDir)
        throws IOException, InterruptedException {
        SearchJobManager manager = newManager();
        try {
            SearchJob job = manager.submit(
                "x + 0", InputType.TERM, "FAST_SIMPLIFY", List.of());
            await(
                Duration.ofSeconds(2),
                () -> manager.get(job.id())
                    .map(current -> current.state() == SearchJob.State.DONE)
                    .orElse(false),
                "search job did not complete before checkpoint"
            );

            Path checkpoint = tempDir.resolve("jobs.json");
            manager.checkpoint(checkpoint);
            assertTrue(Files.exists(checkpoint));

            SearchJobManager restored = newManager();
            try {
                restored.restore(checkpoint);
                SearchJob recovered = restored.get(job.id()).orElseThrow();
                assertEquals(job.id(), recovered.id());
                assertEquals(SearchJob.State.PAUSED, recovered.state());
                assertEquals("paused", recovered.activePhase());
                assertTrue(recovered.resumable());
            } finally {
                restored.shutdown();
            }
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void cancelMarksJobCancelled() throws InterruptedException {
        SearchJobManager manager = newManager();
        try {
            SearchJob job = manager.submit(
                "(x + 0) * 1", InputType.TERM, "FAST_SIMPLIFY", List.of());
            manager.cancel(job.id());
            await(
                Duration.ofSeconds(1),
                () -> manager.get(job.id())
                    .map(current -> current.state() == SearchJob.State.CANCELLED)
                    .orElse(false),
                "cancelled job did not expose its terminal state"
            );

            SearchJob result = manager.get(job.id()).orElseThrow();
            assertEquals(SearchJob.State.CANCELLED, result.state());
            assertEquals("cancelled", result.activePhase());
            assertFalse(result.resumable());
        } finally {
            manager.shutdown();
        }
    }
}
