package de.regelsuche.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        assertTrue(markdown.contains("# Gefundene Umformungen"));
        assertTrue(markdown.contains("## 1. (x+3)^2 → x^2+6*x+9"));
        assertTrue(markdown.contains("\\rightarrow"));
        assertTrue(markdown.contains("#### Status\nVALIDATED_BY_EXAMPLES"));
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

    @Test
    void jsonExportContainsCompleteTransformationSteps() {
        String json = new DefaultTransformationExportService().exportJson(List.of(sampleTransformation()), List.of());

        assertTrue(json.contains("\"scores\""));
        assertTrue(json.contains("\"discoveredAt\":\"1970-01-01T00:00:00Z\""));
        assertTrue(json.contains("\"canonicalHash\":\"hash-1\""));
        assertTrue(json.contains("\"steps\""));
        assertTrue(json.contains("\"index\":0"));
        assertTrue(json.contains("\"beforeExpression\":\"(x+3)^2\""));
        assertTrue(json.contains("\"afterExpression\":\"(x+3)*(x+3)\""));
        assertTrue(json.contains("\"ruleId\":\"power_to_product\""));
        assertTrue(json.contains("\"ruleKind\":\"EXPAND\""));
        assertTrue(json.contains("\"scoreBefore\":42"));
        assertTrue(json.contains("\"scoreAfter\":35"));
        assertTrue(json.contains("\"equivalencePreserving\":true"));
        assertTrue(json.contains("\"explanation\":\"power as product\""));
    }

    @Test
    void jsonExportContainsReusableRules() {
        String json = new DefaultTransformationExportService().exportJson(List.of(), List.of(sampleRule()));

        assertTrue(json.contains("\"reusableRules\""));
        assertFalse(json.contains("\"rules\""));
        assertTrue(json.contains("\"id\":\"rule-1\""));
        assertTrue(json.contains("\"leftPattern\":\"x^2 + 2*A*x + A^2\""));
        assertTrue(json.contains("\"rightPattern\":\"(x + A)^2\""));
        assertTrue(json.contains("\"parameterRelations\":[\"N1 = 2*A\",\"N2 = A^2\"]"));
        assertTrue(json.contains("\"proofStatus\":\"VALIDATED_BY_EXAMPLES\""));
        assertTrue(json.contains("\"knownRuleStatus\":\"MATCHES_KNOWN_RULE\""));
        assertTrue(json.contains("\"supportingExamples\":3"));
        assertTrue(json.contains("\"averageImprovement\":12.5"));
        assertTrue(json.contains("\"createdAt\":\"1970-01-01T00:00:00Z\""));
    }

    @Test
    void jsonExportCanBeParsedBack() {
        String json = new DefaultTransformationExportService().exportJson(
            List.of(sampleTransformation()),
            List.of(sampleRule())
        );

        Map<String, Object> parsed = new de.regelsuche.json.JsonReader(json).readObject();
        List<?> transformations = (List<?>) parsed.get("transformations");
        List<?> reusableRules = (List<?>) parsed.get("reusableRules");

        assertEquals(1, transformations.size());
        assertEquals(1, reusableRules.size());
        Map<?, ?> transformation = (Map<?, ?>) transformations.getFirst();
        Map<?, ?> scores = (Map<?, ?>) transformation.get("scores");
        List<?> steps = (List<?>) transformation.get("steps");
        Map<?, ?> step = (Map<?, ?>) steps.getFirst();
        Map<?, ?> rule = (Map<?, ?>) reusableRules.getFirst();

        assertEquals("path-1", transformation.get("id"));
        assertEquals(64, ((Number) ((Map<?, ?>) scores.get("original")).get("weightedTotal")).intValue());
        assertEquals("power_to_product", step.get("ruleId"));
        assertEquals(Boolean.TRUE, step.get("equivalencePreserving"));
        assertEquals("rule-1", rule.get("id"));
        assertEquals(List.of("N1 = 2*A", "N2 = A^2"), rule.get("parameterRelations"));
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

    private ReusableRule sampleRule() {
        return new ReusableRule(
            "rule-1",
            "x^2 + 2*A*x + A^2",
            "(x + A)^2",
            List.of("N1 = 2*A", "N2 = A^2"),
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            RuleStatus.MATCHES_KNOWN_RULE,
            3,
            12.5,
            Instant.EPOCH
        );
    }

}
