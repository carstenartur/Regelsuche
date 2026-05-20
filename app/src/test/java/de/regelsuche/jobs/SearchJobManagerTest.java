package de.regelsuche.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        SearchJob job = manager.submit("(x + 0) * 1", InputType.TERM, "FAST_SIMPLIFY", List.of());
        assertNotNull(job.id());
        // wait until done or cancelled
        for (int i = 0; i < 80; i++) {
            SearchJob current = manager.get(job.id()).orElseThrow();
            if (current.state() == SearchJob.State.DONE || current.state() == SearchJob.State.FAILED) {
                break;
            }
            Thread.sleep(50);
        }
        SearchJob result = manager.get(job.id()).orElseThrow();
        assertEquals(SearchJob.State.DONE, result.state(), "job did not finish: " + result);
        manager.shutdown();
    }

    @Test
    void checkpointAndRestorePreserveJobMetadata(@TempDir Path tempDir) throws IOException, InterruptedException {
        SearchJobManager manager = newManager();
        SearchJob job = manager.submit("x + 0", InputType.TERM, "FAST_SIMPLIFY", List.of());
        for (int i = 0; i < 40; i++) {
            SearchJob current = manager.get(job.id()).orElseThrow();
            if (current.state() == SearchJob.State.DONE) {
                break;
            }
            Thread.sleep(50);
        }
        Path checkpoint = tempDir.resolve("jobs.json");
        manager.checkpoint(checkpoint);
        assertTrue(Files.exists(checkpoint));

        SearchJobManager restored = newManager();
        restored.restore(checkpoint);
        SearchJob recovered = restored.get(job.id()).orElseThrow();
        assertEquals(job.id(), recovered.id());
        assertEquals(SearchJob.State.PAUSED, recovered.state());
        manager.shutdown();
        restored.shutdown();
    }

    @Test
    void cancelMarksJobCancelled() throws InterruptedException {
        SearchJobManager manager = newManager();
        SearchJob job = manager.submit("(x + 0) * 1", InputType.TERM, "FAST_SIMPLIFY", List.of());
        manager.cancel(job.id());
        Thread.sleep(50);
        SearchJob result = manager.get(job.id()).orElseThrow();
        assertEquals(SearchJob.State.CANCELLED, result.state());
        manager.shutdown();
    }
}
