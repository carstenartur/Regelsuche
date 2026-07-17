package de.regelsuche.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.benchmarks.ComparativeBenchmark.CapabilityClaim;
import de.regelsuche.benchmarks.ComparativeBenchmark.ClaimStatus;
import de.regelsuche.benchmarks.ComparativeBenchmark.Report;
import de.regelsuche.benchmarks.ComparativeBenchmark.Track;
import de.regelsuche.benchmarks.ComparativeBenchmarkSystems.SearchSystem;
import de.regelsuche.search.strategy.BestFirstSearchStrategy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComparativeBenchmarkBundleWriterTest {

    @Test
    void rewriteRemovesStaleArtifactsAndRetainsEveryCanonicalObject(
        @TempDir Path directory
    ) throws Exception {
        var benchmarkCase = ComparativeBenchmarkCatalog.searchCases().getFirst();
        var parity = ComparativeBenchmarkCatalog.searchParity(
            List.of(benchmarkCase));
        var system = new SearchSystem(
            "best-first",
            "test",
            new BestFirstSearchStrategy(),
            List.of());
        var configuration =
            ComparativeBenchmarkCatalog.searchConfiguration(system, parity);
        var result = new ComparativeBenchmarkExecutor().runSearch(
            system, configuration, benchmarkCase);
        var claim = CapabilityClaim.create(
            "single-search-result",
            Track.TARGET_DIRECTED_SEARCH,
            ClaimStatus.SUPPORTED,
            "the pinned search target was reached",
            List.of(result.contentHash()),
            List.of("SINGLE_CASE_TEST"));
        Report report = Report.create(
            "bundle-writer-test/v1",
            List.of(parity),
            List.of(configuration),
            List.of(benchmarkCase),
            List.of(result),
            List.of(claim),
            List.of());

        ComparativeBenchmarkBundleWriter writer =
            new ComparativeBenchmarkBundleWriter();
        writer.write(directory, report);
        Path stale = directory.resolve("results/999-stale.json");
        Files.writeString(stale, "stale");
        writer.write(directory, report);

        assertFalse(Files.exists(stale));
        assertEquals(report.toCanonicalJson(),
            Files.readString(directory.resolve("report.json")));
        assertTrue(Files.isRegularFile(directory.resolve(
            "parity-manifests/target-directed-shared-budget_v1.json")));
        assertTrue(Files.isRegularFile(directory.resolve(
            "configurations/search-best-first.json")));
        assertTrue(Files.isRegularFile(directory.resolve(
            "cases/target-add-zero.json")));
        assertTrue(Files.isRegularFile(directory.resolve(
            "claims/single-search-result.json")));
        try (var resultFiles = Files.list(directory.resolve("results"))) {
            assertEquals(1L, resultFiles.count());
        }
    }
}
