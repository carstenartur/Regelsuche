package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmark.DiscoveryExperimentRunner.HistoricalWitnessPruningDiagnostic;
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
    void diversityRecoveryIsBoundAndByteStable(@TempDir Path temporary)
            throws Exception {
        Corpus corpus = singleCase("distribution-fitness-valley-control");
        HistoricalRediscoveryAtlas atlas = new HistoricalRediscoveryAtlas();
        HistoricalRediscoveryAtlas.AtlasReport report = atlas.run(corpus);

        Path atlasDirectory = temporary.resolve("atlas");
        HistoricalRediscoveryRunArtifact.begin(atlasDirectory);
        HistoricalRediscoveryAtlas.WrittenArtifacts atlasArtifacts =
            atlas.write(atlasDirectory, report);
        HistoricalRediscoveryRunArtifact.commit(
            atlasDirectory, corpus, report, atlasArtifacts);

        HistoricalWitnessPruningDiagnostic pruning =
            new HistoricalWitnessPruningDiagnostic();
        List<HistoricalWitnessPruningDiagnostic.CaseDiagnostic> pruningCases =
            pruning.run(corpus, report);
        Path pruningPath = pruning.write(
            temporary.resolve("pruning"), corpus, report, pruningCases);

        HistoricalWitnessPolicyComparison comparison =
            new HistoricalWitnessPolicyComparison();
        HistoricalWitnessPolicyComparison.Result first = comparison.execute(
            corpus, atlasDirectory, pruningPath, temporary.resolve("first"));
        HistoricalWitnessPolicyComparison.Result second = comparison.execute(
            corpus, atlasDirectory, pruningPath, temporary.resolve("second"));

        assertArrayEquals(
            Files.readAllBytes(first.path()),
            Files.readAllBytes(second.path()));
        assertEquals(first.contentHash(), second.contentHash());
        assertTrue(first.contentHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(1, first.comparisons().size());

        HistoricalWitnessPolicyComparison.Comparison retained =
            first.comparisons().get(0);
        assertEquals("DIVERSITY_REACHED_RELATION", retained.status());
        assertTrue(retained.compared());
        assertTrue(retained.diversityReached());
        assertTrue(retained.prefixGain() > 0);
        assertFalse(retained.scalarFirstLossReason().isBlank());
        assertEquals(retained.diversityPrefixLength()
            - retained.scalarPrefixLength(), retained.prefixGain());

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
                temporary.resolve("tampered")));
    }

    private static Corpus singleCase(String id) {
        Corpus full = HistoricalRediscoveryCorpus.load();
        HistoricalRediscoveryCorpus.Case selected = full.cases().stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow();
        return new Corpus(
            full.schema(),
            full.evidenceStatus(),
            full.inventoryRevision(),
            full.claimBoundary(),
            full.contentSha256(),
            List.of(selected));
    }
}
