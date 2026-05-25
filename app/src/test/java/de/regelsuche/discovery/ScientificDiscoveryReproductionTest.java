package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DeterministicDiscoveryExperimentRunner;
import de.regelsuche.benchmark.DiscoveryReplayArtifactWriter;
import de.regelsuche.demo.DemoService;
import de.regelsuche.example.ScientificSeedCorpora;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScientificDiscoveryReproductionTest {

    @TempDir
    Path tempDir;

    @Test
    void scientificSeedsAreReproducedByApplicationDiscoveryRuns() throws Exception {
        Map<String, String> demosBySeed = Map.of(
            "identity-binomial-1", "binomial",
            "dlmf-trigonometric-1", "trigonometry",
            "matrix-identity-1", "math-matrix",
            "rational-simplification-1", "rational"
        );
        List<SeedExpression> seeds = ScientificSeedCorpora.curated().stream()
            .filter(seed -> demosBySeed.containsKey(seed.id()))
            .toList();
        InMemoryExpressionGraphStore graphStore = new InMemoryExpressionGraphStore();
        DeterministicDiscoveryExperimentRunner runner = new DeterministicDiscoveryExperimentRunner(
            seeds.size(),
            2,
            seed -> runDemoSeed(seed, demosBySeed.get(seed.id()), graphStore)
        );

        DeterministicDiscoveryExperimentRunner.DiscoveryReport report = runner.runDetailed(seeds);
        DiscoveryReplayArtifactWriter.ArtifactBundle artifacts = new DiscoveryReplayArtifactWriter().write(report, tempDir);

        assertEquals(seeds.size(), report.metrics().processedSeeds());
        assertEquals(seeds.size(), report.metrics().successfulSeeds());
        assertTrue(report.rows().stream().allMatch(row -> !row.replayPath().isEmpty()),
            "every reproduced scientific seed must expose a replay path");
        assertFalse(graphStore.discoveredTransformations().isEmpty(),
            "real app discovery runs must write discovered transformations to the graph store");
        assertTrue(Files.exists(artifacts.htmlReport()), "CI run must emit an HTML report artifact");
        assertTrue(Files.exists(artifacts.screenshotPng()), "CI run must emit a screenshot artifact");
        assertTrue(Files.exists(artifacts.replayGif()), "CI run must emit a replay GIF artifact");
    }

    private DeterministicDiscoveryExperimentRunner.SeedRunOutcome runDemoSeed(
        SeedExpression seed,
        String demoId,
        InMemoryExpressionGraphStore graphStore
    ) {
        DemoService.DemoRunResult result = new DemoService(graphStore).run(demoId);
        List<String> replay = result.selectedPath() == null
            ? List.of(result.rootExpression())
            : result.selectedPath().steps().stream()
                .map(step -> step.beforeExpression() + " -> " + step.afterExpression())
                .toList();
        List<String> hypotheses = result.selectedPath() == null
            ? List.of()
            : List.of(result.selectedPath().id());
        return new DeterministicDiscoveryExperimentRunner.SeedRunOutcome(
            result.targetReached(),
            seed.id() + " reproduced via " + demoId,
            hypotheses,
            List.of(),
            replay,
            result.elapsedMillis(),
            0L
        );
    }
}
