package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskKnowledgeQueryComparisonTest {
    @Test void completeComparisonPreservesQualityAndSeparatesRepeatedFromDistinctInputs(@TempDir Path directory) throws Exception {
        var report = new TaskKnowledgeQueryComparison().run(directory.resolve("study"), 1);
        assertEquals(6, report.arms().size());
        assertEquals(3, report.comparisons().size());
        assertTrue(report.comparisons().stream().allMatch(TaskKnowledgeQueryComparison.Comparison::qualityNonRegressing));
        for (var arm : report.arms()) {
            assertTrue(arm.accountingComplete(), arm.failure());
            assertEquals(2, arm.acquisition().size());
            assertTrue(arm.setupWork() > 0);
            assertTrue(arm.queries().stream().allMatch(TaskKnowledgeQueryComparison.Row::verified));
            assertEquals(arm.setupWork() + arm.queries().stream().mapToLong(row -> row.queryWork() + row.auditWork()).sum(), arm.knownWork());
            long expectedHits = arm.profile() == TaskKnowledgeQueryComparison.Profile.RETAINED_PATH
                    && arm.scenario() == TaskKnowledgeQueryComparison.Scenario.REPEATED_SIXTEEN ? 84 : 0;
            assertEquals(expectedHits, arm.statistics().hits());
            assertEquals(arm.scenario().blocks() * 8, arm.queries().size());
            assertEquals(arm.scenario().blocks() * 7, arm.queries().stream().filter(TaskKnowledgeQueryComparison.Row::solved).count());
            assertTrue(arm.queries().stream().filter(TaskKnowledgeQueryComparison.Row::reused)
                .allMatch(row -> row.verificationWork() > 0 && row.replayWork() > 0));
            if (arm.scenario() != TaskKnowledgeQueryComparison.Scenario.REPEATED_SIXTEEN) {
                assertEquals(0, arm.statistics().admissions());
                assertEquals(0, arm.statistics().retainedQueries());
            }
        }
    }
}
