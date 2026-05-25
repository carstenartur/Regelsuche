package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.example.ScientificSeedCorpora;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.persistence.PersistenceConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScientificDiscoveryReproductionTest {

    @TempDir
    Path tempDir;

    @Test
    void scientificSeedsFlowThroughAppWiringFromSeedToReplayReport() throws Exception {
        List<SeedExpression> seeds = coreScientificSeeds();
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            ScientificDiscoveryWorkflow.RunResult run = workflow.run(
                "exp-in-memory-scientific",
                seeds,
                seeds.size(),
                2,
                tempDir.resolve("artifacts")
            );

            DeterministicDiscoveryExperimentRunner.DiscoveryReport report = run.report();
            assertEquals(seeds.size(), report.metrics().processedSeeds());
            assertEquals(seeds.size(), report.metrics().successfulSeeds(), report.renderDeterministicJson());
            assertCategoryCovered(report, "binomial");
            assertCategoryCovered(report, "geometric-series");
            assertCategoryCovered(report, "factorization");
            assertCategoryCovered(report, "trigonometric");
            assertCategoryCovered(report, "matrix");
            assertCategoryCovered(report, "rational");
            assertTrue(report.rows().stream().allMatch(row -> !row.replayPath().isEmpty()),
                "every reproduced scientific seed must expose replay steps");
            assertFalse(run.context().graphStore().discoveredTransformations().isEmpty(),
                "workflow must write discovered transformations through app wiring");
            assertTrue(Files.readString(run.artifacts().htmlReport()).contains("replay-step"));
            assertTrue(Files.exists(tempDir.resolve("artifacts").resolve("discovery-report.md")));
            assertTrue(Files.exists(tempDir.resolve("artifacts").resolve("discovery-replay.json")));
            assertTrue(Files.size(run.artifacts().screenshotPng()) > 0);
            assertTrue(Files.size(run.artifacts().replayGif()) > 0);
        }
    }

    @Test
    void deterministicJsonIsByteStableAcrossIdenticalRuns() {
        List<SeedExpression> seeds = coreScientificSeeds();
        String first;
        String second;
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            first = workflow.run("exp-a", seeds, seeds.size(), 1, tempDir.resolve("a"))
                .report().renderDeterministicJson();
        }
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            second = workflow.run("exp-b", seeds, seeds.size(), 1, tempDir.resolve("b"))
                .report().renderDeterministicJson();
        }
        assertEquals(first, second);
        assertTrue(first.contains("volatileFields"));
    }

    @Test
    void budgetAndParallelismKeepScientificResultsDeterministic() {
        List<SeedExpression> seeds = ScientificSeedCorpora.curated();
        DeterministicDiscoveryExperimentRunner.DiscoveryReport serial;
        DeterministicDiscoveryExperimentRunner.DiscoveryReport parallel;
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            serial = workflow.run("exp-serial", seeds, 6, 1, tempDir.resolve("serial")).report();
        }
        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            parallel = workflow.run("exp-parallel", seeds, 6, 4, tempDir.resolve("parallel")).report();
        }
        assertEquals(serial.renderDeterministicJson(), parallel.renderDeterministicJson());

        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            DeterministicDiscoveryExperimentRunner.DiscoveryReport aborted = workflow.run(
                "exp-aborted", seeds, 0, 4, tempDir.resolve("aborted")).report();
            assertEquals(0, aborted.metrics().processedSeeds());
            assertTrue(aborted.renderDeterministicJson().contains("\"rows\":[]"));
        }
    }

    private static List<SeedExpression> coreScientificSeeds() {
        return ScientificSeedCorpora.curated().stream()
            .filter(seed -> List.of(
                "binomial",
                "geometric-series",
                "factorization",
                "trigonometric",
                "matrix",
                "rational"
            ).contains(seed.category()))
            .toList();
    }

    private static void assertCategoryCovered(
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report,
        String category
    ) {
        assertTrue(report.rows().stream().anyMatch(row -> row.seed().category().equals(category) && row.success()),
            "missing successful reproduction for " + category + ": " + report.renderDeterministicJson());
    }
}
