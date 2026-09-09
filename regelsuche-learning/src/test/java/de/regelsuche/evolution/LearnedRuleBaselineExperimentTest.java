package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(120)
class LearnedRuleBaselineExperimentTest {
    @Test
    void fourProfilesShareGoalsBudgetsAndPrimitiveAuditStrengthWithRankedInitiallyIdenticalToNaive() throws Exception {
        var report = LearnedRuleBaselineExperiment.run();
        assertEquals(LearnedRuleBaselineExperiment.cases().size() * 4 * 4, report.rows().size());
        assertTrue(report.knowledge().plan().isPresent(), "the comparison must contain actual frozen TRAIN knowledge");
        assertEquals(16, report.rows().stream().filter(row -> row.search() == null).count());
        for (var row : report.rows()) {
            if (row.search() == null) continue;
            var diagnostic = row.diagnostics();
            var retained = row.search().reached() ? row.search().reachedState() : row.search().bestState();
            assertEquals(retained.primitiveDepth(), diagnostic.verificationWork(), "macro proof expansion is not free");
            assertEquals(diagnostic.generatedSuccessors(), diagnostic.enqueuedSuccessors() + diagnostic.discardedSuccessors());
            if (row.search().reached()) assertTrue(diagnostic.totalWork() <= row.budget());
            if (row.example().id().equals("near-miss")) assertFalse(row.search().reached());
            if (row.profile() == LearnedSearchProfile.LEARNED_RANKED) {
                var naive = report.rows().stream().filter(other -> other.example().equals(row.example())
                    && other.budget() == row.budget() && other.profile() == LearnedSearchProfile.LEARNED_NAIVE).findFirst().orElseThrow();
                assertEquals(naive.search().toCanonicalJson(), row.search().toCanonicalJson());
                assertEquals(naive.diagnostics(), row.diagnostics());
            }
        }
        assertTrue(report.rows().stream().anyMatch(row -> row.search() != null && row.search().reached()
            && row.profile() == LearnedSearchProfile.BASE && !row.example().id().equals("already-target")));
        assertTrue(report.rows().stream().anyMatch(row -> row.diagnostics() != null && row.diagnostics().unconsumedSuccessors() > 0));
        Path directory = LearnedRuleBaselineExperiment.write(report, Path.of("build/reports/learned-rule-baseline"));
        assertEquals(report.toCanonicalJson(), Files.readString(directory.resolve("report.json")));
        assertFalse(Files.readString(directory.resolve("manifest.json")).contains("walltime"));
        assertFalse(report.toCanonicalJson().contains("wallNanos"));
        assertTrue(Files.isRegularFile(directory.resolve("knowledge.json")));
        assertThrows(IllegalArgumentException.class, () -> LearnedRuleBaselineExperiment.runCase(report.knowledge(),
            new LearnedRuleBaselineExperiment.Case("leak", TraceStrategyTransferExample.trainingInputs().getFirst().expression(), "x"),
            LearnedSearchProfile.BASE, 512));
    }
}
