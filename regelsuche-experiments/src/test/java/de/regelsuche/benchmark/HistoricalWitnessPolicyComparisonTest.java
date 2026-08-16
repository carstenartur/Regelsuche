package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.HistoricalRediscoveryCorpus.Corpus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HistoricalWitnessPolicyComparisonTest {

    @Test
    @Timeout(240)
    void diversityRecoversTheFrozenFitnessValleyAndEvidenceIsStable(
            @TempDir Path temporary) throws Exception {
        Corpus corpus = fitnessValleyCorpus();
        HistoricalRediscoveryAtlas atlas = new HistoricalRediscoveryAtlas();
        HistoricalRediscoveryAtlas.AtlasReport report = atlas.run(corpus);

        Path atlasDirectory = temporary.resolve("atlas");
        HistoricalRediscoveryRunArtifact.begin(atlasDirectory);
        HistoricalRediscoveryAtlas.WrittenArtifacts atlasFiles =
            atlas.write(atlasDirectory, report);
        HistoricalRediscoveryRunArtifact.commit(
            atlasDirectory, corpus, report, atlasFiles);

        DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic pruning =
            new DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic();
        List<DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic.CaseDiagnostic>
            pruningCases = pruning.run(corpus, report);
        Path pruningPath = pruning.write(
            temporary.resolve("scalar"), corpus, report, pruningCases);

        HistoricalWitnessPolicyComparison comparison =
            new HistoricalWitnessPolicyComparison();
        HistoricalWitnessPolicyComparison.Execution first = comparison.execute(
            corpus,
            atlasDirectory,
            pruningPath,
            temporary.resolve("comparison-first"));
        HistoricalWitnessPolicyComparison.Execution second = comparison.execute(
            corpus,
            atlasDirectory,
            pruningPath,
            temporary.resolve("comparison-second"));

        HistoricalWitnessPolicyComparison.CaseComparison retained =
            first.cases().get(0);
        assertEquals("DIVERSITY_REACHED_RELATION", retained.status());
        assertFalse(retained.scalar().reached());
        assertTrue(retained.diversity().reached());
        assertTrue(retained.prefixGain() > 0, retained.toString());
        assertEquals(first.contentHash(), second.contentHash());
        assertArrayEquals(
            Files.readAllBytes(first.path()),
            Files.readAllBytes(second.path()));
        assertTrue(first.contentHash().matches("sha256:[0-9a-f]{64}"));

        Files.writeString(
            pruningPath,
            Files.readString(pruningPath, StandardCharsets.UTF_8) + "\n",
            StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> comparison.execute(
                corpus,
                atlasDirectory,
                pruningPath,
                temporary.resolve("comparison-tampered")));
    }

    private static Corpus fitnessValleyCorpus() {
        Corpus full = HistoricalRediscoveryCorpus.load();
        List<HistoricalRediscoveryCorpus.Case> selected = full.cases().stream()
            .filter(value -> value.id().equals(
                "distribution-fitness-valley-control"))
            .toList();
        assertEquals(1, selected.size());
        return new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            selected);
    }
}
