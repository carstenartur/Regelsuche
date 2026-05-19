package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryPersistenceExportTest {
    @Test
    void storesDiscoveredTransformationWithCompletePath() {
        InMemoryExpressionGraphStore store = new InMemoryExpressionGraphStore();
        DiscoveredTransformation transformation = sampleTransformation();

        store.saveDiscoveredTransformation(transformation);

        assertEquals(1, store.discoveredTransformations().size());
        assertEquals("power_to_product", store.discoveredTransformations().getFirst().steps().getFirst().ruleId());
    }

    @Test
    void exportsTransformationPathAsMarkdown() {
        String markdown = new DefaultTransformationExportService().exportMarkdown(List.of(sampleTransformation()));

        assertTrue(markdown.contains("### Gefundene Umformung"));
        assertTrue(markdown.contains("\\rightarrow"));
        assertTrue(markdown.contains("Status: VALIDATED_BY_EXAMPLES"));
    }

    @Test
    void exportsTransformationPathAsLatex() {
        String latex = new DefaultTransformationExportService().exportLatex(List.of(sampleTransformation()));

        assertTrue(latex.contains("\\begin{align*}"));
        assertTrue(latex.contains("\\rightarrow"));
    }

    @Test
    void exportsTransformationPathAsMermaid() {
        String mermaid = new DefaultTransformationExportService().exportMermaid(List.of(sampleTransformation()));

        assertTrue(mermaid.startsWith("graph TD"));
        assertTrue(mermaid.contains("-->|power_to_product|"));
    }

    private DiscoveredTransformation sampleTransformation() {
        ExpressionScore originalScore = new ExpressionScore(42, 10, 8, 4, 0);
        ExpressionScore improvedScore = new ExpressionScore(17, 5, 4, 2, 0);
        return new DiscoveredTransformation(
            "path-1",
            "(x+3)^2",
            "x^2+6*x+9",
            List.of(
                new TransformationStep(0, "(x+3)^2", "(x+3)*(x+3)", "power_to_product", RewriteKind.EXPAND, 42, 35, true, "power as product"),
                new TransformationStep(1, "(x+3)*(x+3)", "x^2+6*x+9", "distribute", RewriteKind.EXPAND, 35, 17, true, "distribute")
            ),
            originalScore,
            improvedScore,
            originalScore.improvementTo(improvedScore),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Instant.EPOCH,
            "hash-1"
        );
    }
}
