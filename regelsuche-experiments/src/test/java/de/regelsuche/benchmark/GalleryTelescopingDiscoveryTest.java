package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalEqualWorkSearchComparison.CaseStatus;
import de.regelsuche.benchmark.HistoricalEqualWorkSearchComparison.Outcome;
import de.regelsuche.benchmark.HistoricalProductionSearchComparison.ComparisonStatus;
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
        ComparisonFixture fixture = compareCase(
            "distribution-fitness-valley-control");
        var report = fixture.primary();
        HistoricalProductionSearchComparison comparison =
            new HistoricalProductionSearchComparison();
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

    @Test
    void semanticScalarMatchDoesNotRequireAnOracleWitness() throws Exception {
        ComparisonFixture fixture = compareCase("sophie-germain");
        var result = fixture.primary().cases().get(0);
        var equalWork = fixture.equalWork().cases().get(0);

        assertEquals(
            ComparisonStatus.NOT_APPLICABLE_NO_PRODUCTION_WITNESS,
            result.status());
        assertTrue(result.scalar().reachedRelation());
        assertEquals(0, result.oracleWitnessStepCount());
        assertEquals(CaseStatus.NO_PRODUCTION_WITNESS, equalWork.status());
        assertTrue(equalWork.checkpoints().isEmpty());
    }

    @Test
    @Timeout(240)
    void diversityAdvantageSurvivesAnEqualConsumedWorkCheckpoint(
        @TempDir Path directory
    ) throws Exception {
        ComparisonFixture fixture = compareCase(
            "distribution-fitness-valley-control");
        var report = fixture.equalWork();
        var result = report.cases().get(0);

        assertEquals(
            CaseStatus.EXECUTED_ORACLE_WITNESS_SCALAR_MISS,
            result.status());
        var checkpoint = result.checkpoints().stream()
            .filter(value -> value.equalConsumedWork())
            .filter(value -> value.outcome()
                == Outcome.DIVERSITY_ONLY_COMPLETE_WITNESS)
            .findFirst()
            .orElseThrow();
        assertFalse(checkpoint.scalar().reachedRelation());
        assertTrue(checkpoint.diversity().reachedRelation());
        assertEquals(
            checkpoint.scalar().engineCalls(),
            checkpoint.diversity().engineCalls());
        assertEquals(
            checkpoint.scalar().admittedPrimitiveSteps(),
            checkpoint.diversity().admittedPrimitiveSteps());
        assertTrue(
            report.summary().equalWorkDiversityCompleteWitnessCount() > 0);

        HistoricalEqualWorkSearchComparison comparison =
            new HistoricalEqualWorkSearchComparison();
        Path first = comparison.write(directory.resolve("first"), report);
        Path second = comparison.write(directory.resolve("second"), report);
        assertArrayEquals(
            Files.readAllBytes(first),
            Files.readAllBytes(second));
    }

    private static ComparisonFixture compareCase(String id) throws Exception {
        HistoricalRediscoveryCorpus.Corpus full =
            HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case selected = full.cases().stream()
            .filter(value -> value.id().equals(id))
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
        HistoricalProductionSearchComparison primary =
            new HistoricalProductionSearchComparison();
        var primaryReport = primary.run(corpus, atlas, pruningCases);
        var equalWorkReport =
            new HistoricalEqualWorkSearchComparison().run(corpus, atlas);
        return new ComparisonFixture(primaryReport, equalWorkReport);
    }

    private record ComparisonFixture(
        HistoricalProductionSearchComparison.Report primary,
        HistoricalEqualWorkSearchComparison.Report equalWork
    ) {
    }
}
