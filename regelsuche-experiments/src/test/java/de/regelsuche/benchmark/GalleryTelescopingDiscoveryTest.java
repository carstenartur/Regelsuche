package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalProductionSearchComparison.ComparisonStatus;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.Report;
import de.regelsuche.example.SeedExpression;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class GalleryTelescopingDiscoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void galleryEntryIsGeneratedFromReplayEvidence() throws Exception {
        DeterministicDiscoveryExperimentRunner.SeedRunReport row =
            new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("telescoping", "1 / (n * (n + 1))", "test",
                    "telescoping-fraction",
                    List.of("operator:telescoping-fraction"), List.of()),
                true,
                "validated telescoping replay",
                List.of("1 / n - 1 / (n + 1)"),
                List.of(),
                CounterexampleSearchService.Status.NO_COUNTEREXAMPLE_FOUND,
                List.of(),
                List.of(),
                "",
                List.of("1 / (n * (n + 1))", "1 / n - 1 / (n + 1)"),
                DiscoveryResultKind.TRANSFORMED,
                List.of("hypothesis_telescoping_fraction"),
                1L,
                0L,
                Set.of(
                    DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED,
                    DiscoveryEvidenceKind.SIMPLIFIED)
            );
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report =
            new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
                List.of(row),
                new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(
                    1, 1, 1, 0, 1L, 0L),
                1L
            );

        DiscoveryReplayArtifactWriter.ArtifactBundle bundle =
            new DiscoveryReplayArtifactWriter().write(report, tempDir);
        String markdown = Files.readString(bundle.markdownReport());

        assertTrue(markdown.contains("Telescoping fraction discovery"));
        assertTrue(markdown.contains("1 / n - 1 / (n + 1)"));
        assertTrue(markdown.contains(
            "validation status: NO_COUNTEREXAMPLE_FOUND"));
    }
}

class HistoricalProductionSearchComparisonTest {

    @Test
    @Timeout(240)
    void frozenPolicyControlIsPositiveAndByteStable(@TempDir Path directory)
            throws Exception {
        HistoricalRediscoveryCorpus.Corpus full =
            HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case selected = full.cases().stream()
            .filter(value -> value.id().equals(
                "distribution-fitness-valley-control"))
            .findFirst()
            .orElseThrow();
        HistoricalRediscoveryCorpus.Corpus corpus =
            new HistoricalRediscoveryCorpus.Corpus(
                full.schema(),
                full.evidenceStatus(),
                full.inventoryRevision(),
                full.claimBoundary(),
                full.contentSha256(),
                List.of(selected));
        HistoricalRediscoveryAtlas.AtlasReport atlas =
            new HistoricalRediscoveryAtlas().run(corpus);
        DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic pruning =
            new DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic();
        var pruningCases = pruning.run(corpus, atlas);

        HistoricalProductionSearchComparison comparison =
            new HistoricalProductionSearchComparison();
        Report report = comparison.run(corpus, atlas, pruningCases);
        assertEquals(
            ComparisonStatus.DIVERSITY_RECOVERS_COMPLETE_WITNESS,
            report.cases().get(0).status());
        assertTrue(report.cases().get(0).prefixDelta() > 0);
        assertTrue(report.summary().diversityReachedRelationCount() > 0);
        assertTrue(report.toCanonicalJson().matches(
            ".*\"contentHash\":\"sha256:[0-9a-f]{64}\"}\\z"));

        Path first = comparison.write(directory.resolve("first"), report);
        Path second = comparison.write(directory.resolve("second"), report);
        assertArrayEquals(
            Files.readAllBytes(first),
            Files.readAllBytes(second));
    }
}
