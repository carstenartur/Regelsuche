package de.regelsuche.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.validation.CounterexampleSearchService;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import de.regelsuche.validation.DiscoveryResultKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalleryTelescopingDiscoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void galleryEntryIsGeneratedFromReplayEvidence() throws Exception {
        DeterministicDiscoveryExperimentRunner.SeedRunReport row =
            new DeterministicDiscoveryExperimentRunner.SeedRunReport(
                new SeedExpression("telescoping", "1 / (n * (n + 1))", "test",
                    "telescoping-fraction", List.of("operator:telescoping-fraction"), List.of()),
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
                Set.of(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED, DiscoveryEvidenceKind.SIMPLIFIED)
            );
        DeterministicDiscoveryExperimentRunner.DiscoveryReport report =
            new DeterministicDiscoveryExperimentRunner.DiscoveryReport(
                List.of(row),
                new DeterministicDiscoveryExperimentRunner.DiscoveryMetrics(1, 1, 1, 0, 1L, 0L),
                1L
            );

        DiscoveryReplayArtifactWriter.ArtifactBundle bundle =
            new DiscoveryReplayArtifactWriter().write(report, tempDir);
        String markdown = Files.readString(bundle.markdownReport());

        assertTrue(markdown.contains("Telescoping fraction discovery"));
        assertTrue(markdown.contains("1 / n - 1 / (n + 1)"));
        assertTrue(markdown.contains("validation status: NO_COUNTEREXAMPLE_FOUND"));
    }
}
