package de.regelsuche.docs;

import de.regelsuche.proof.ProofPolicy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryCandidateReportWriterTest {

    @Test
    void candidateReportShowsStructuredAblationMetrics(@TempDir Path tempDir) throws Exception {
        AblationEvidence evidence = AblationEvidence.compare(
            true,
            2,
            20,
            true,
            4,
            100,
            "candidate shortens search"
        );
        PromotionRecord record = new PromotionRecord(
            "candidate-a",
            "discovery-campaign-test",
            "2026-01-01",
            "factorization",
            PromotionStage.PROMOTED,
            "x*(y+1)+z*(y+1)",
            "(y+1)*(x+z)",
            "AGREE",
            "oracle evidence",
            evidence.ablationStatus(),
            "common_subexpression_discovery",
            "sympy-polynomial-basic",
            List.of(),
            "rationale",
            List.of("common_subexpression_discovery"),
            true,
            List.of(),
            true,
            false,
            false,
            false,
            "",
            List.of(),
            false,
            "",
            evidence,
            ProofPolicy.PROOF_OPTIONAL,
            ""
        );

        new DiscoveryCandidateReportWriter().write(tempDir, "discovery-campaign-test", List.of(record));

        String markdown = Files.readString(tempDir.resolve("discovery-candidates.md"), StandardCharsets.UTF_8);
        assertTrue(markdown.contains("| Ablation evidence |"));
        assertTrue(markdown.contains("pathLength=2"));
        assertTrue(markdown.contains("statesExplored=20"));
        assertTrue(markdown.contains("pathLength=4"));
        assertTrue(markdown.contains("statesExplored=100"));

        String json = Files.readString(tempDir.resolve("discovery-candidates.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("ablationWithCandidate"));
        assertTrue(json.contains("ablationWithoutCandidate"));
        assertTrue(json.contains("ablationImprovementRatio"));
    }
}
