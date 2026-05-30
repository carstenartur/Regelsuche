package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.example.SeedExpression;
import de.regelsuche.persistence.PersistenceConfig;
import de.regelsuche.validation.DiscoveryEvidenceKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalleryHiddenStructureWorkflowTest {
    @TempDir
    Path tempDir;

    @Test
    void galleryEntryIsGeneratedFromRealHiddenStructureReplay() throws Exception {
        SeedExpression hidden = new SeedExpression(
            "hidden-sophie-germain",
            "x^4 + 4",
            "test",
            "hidden-structure",
            List.of(),
            List.of()
        );

        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            ScientificDiscoveryWorkflow.RunResult run = workflow.run(
                "gallery-hidden",
                List.of(hidden),
                1,
                1,
                tempDir.resolve("hidden")
            );

            String markdown = Files.readString(run.artifacts().markdownReport());
            var row = run.report().rows().getFirst();
            assertTrue(markdown.contains("Generated Discovery Gallery"));
            assertTrue(markdown.contains("Sophie-Germain discovery replay"));
            assertTrue(markdown.contains("hypothesis_difference_of_squares_preparation"));
            assertTrue(markdown.contains("ast_square_difference_factor"));
            assertTrue(row.evidence().contains(DiscoveryEvidenceKind.FACTORED));
            assertTrue(row.evidence().contains(DiscoveryEvidenceKind.EQUIVALENCE_VALIDATED));
            assertTrue(row.rulePath().contains("hypothesis_difference_of_squares_preparation"));
            assertTrue(row.rulePath().contains("ast_square_difference_factor"));
        }
    }

    @Test
    void galleryEntryIsNotGeneratedWithoutQualifyingReplay() throws Exception {
        SeedExpression factorization = new SeedExpression(
            "factorization-baseline",
            "x^2 - a^2",
            "test",
            "factorization",
            List.of(),
            List.of()
        );

        try (ScientificDiscoveryWorkflow workflow = ScientificDiscoveryWorkflow.boot(PersistenceConfig.inMemory(), null)) {
            ScientificDiscoveryWorkflow.RunResult run = workflow.run(
                "gallery-baseline",
                List.of(factorization),
                1,
                1,
                tempDir.resolve("baseline")
            );

            String markdown = Files.readString(run.artifacts().markdownReport());
            assertTrue(markdown.contains("No gallery entry emitted"));
            assertFalse(markdown.contains("Sophie-Germain discovery replay"));
        }
    }
}
