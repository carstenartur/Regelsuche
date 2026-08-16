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
    void diversityRecoveryIsContentAddressedAndReproducible(
            @TempDir Path temporary) throws Exception {
        Corpus corpus = subset("distribution-fitness-valley-control");
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
        List<HistoricalWitnessPruningDiagnostic.CaseDiagnostic> scalarCases =
            pruning.run(corpus, report);
        Path scalarPath = pruning.write(
            temporary.resolve("scalar"), corpus, report, scalarCases);

        HistoricalWitnessPolicyComparison comparison =
            new HistoricalWitnessPolicyComparison();
        HistoricalWitnessPolicyCodec.Result first = comparison.execute(
            corpus,
            atlasDirectory,
            scalarPath,
            temporary.resolve("comparison-first"));
        HistoricalWitnessPolicyCodec.Result second = comparison.execute(
            corpus,
            atlasDirectory,
            scalarPath,
            temporary.resolve("comparison-second"));

        assertEquals(first.contentHash(), second.contentHash());
        assertArrayEquals(
            Files.readAllBytes(first.path()),
            Files.readAllBytes(second.path()));
        HistoricalWitnessPolicyComparison.Comparison result =
            first.cases().get(0);
        assertEquals("DIVERSITY_REACHED_RELATION", result.status());
        assertFalse(scalarCases.get(0).status().equals(
            HistoricalWitnessPruningDiagnostic.SCALAR_ALREADY_FOUND));
        assertTrue(result.diversityReached());
        assertTrue(result.prefixGain() > 0);
        assertTrue(first.contentHash().matches("sha256:[0-9a-f]{64}"));
        assertTrue(Files.readString(first.path(), StandardCharsets.UTF_8)
            .contains("\"budgetPolicy\":\""
                + HistoricalWitnessPolicyComparison.BUDGET_POLICY + "\""));

        Files.writeString(
            scalarPath,
            Files.readString(scalarPath, StandardCharsets.UTF_8) + "\n",
            StandardCharsets.UTF_8);
        assertThrows(
            IllegalArgumentException.class,
            () -> comparison.execute(
                corpus,
                atlasDirectory,
                scalarPath,
                temporary.resolve("tampered")));
    }

    private static Corpus subset(String id) {
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
