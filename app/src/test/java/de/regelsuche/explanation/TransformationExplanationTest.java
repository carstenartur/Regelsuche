package de.regelsuche.explanation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests that the Explanation Model can be built and queried without any
 * dependency on Markdown or any other rendering format.
 */
class TransformationExplanationTest {

    @Test
    void transformationExplanationCarriesStructuredDataIndependentlyOfRendering() {
        TransformationExplanation explanation = new TransformationExplanation(
            "complete-square-family",
            "000",
            "x^2 + 6*x + 5",
            "(x + 3)^2 - 4",
            List.of("COMPLETE_SQUARE"),
            List.of("promotion-eligible: oracle and ablation confirmed"),
            List.of("oracle agrees", "evidence present"),
            "AGREE",
            "DEGRADED",
            true,
            true,
            List.of()
        );

        assertEquals("complete-square-family", explanation.candidateId());
        assertEquals("000", explanation.position());
        assertEquals("x^2 + 6*x + 5", explanation.before());
        assertEquals("(x + 3)^2 - 4", explanation.after());
        assertEquals(List.of("COMPLETE_SQUARE"), explanation.rulePath());
        assertEquals(List.of("promotion-eligible: oracle and ablation confirmed"), explanation.interestReasons());
        assertEquals(List.of("oracle agrees", "evidence present"), explanation.pathReasons());
        assertEquals("AGREE", explanation.oracleStatus());
        assertEquals("DEGRADED", explanation.ablationStatus());
        assertTrue(explanation.evidenceExists());
        assertTrue(explanation.measuredImprovement());
    }

    @Test
    void toExplanationProducesGenericStructureWithAllSections() {
        TransformationExplanation transformationExplanation = new TransformationExplanation(
            "candidate-1",
            "01",
            "x + x",
            "2*x",
            List.of("factor_out", "normalize"),
            List.of("macro reused"),
            List.of("oracle agrees", "evidence present"),
            "AGREE",
            "DEGRADED",
            true,
            true,
            List.of("macro.id")
        );

        Explanation explanation = transformationExplanation.toExplanation();

        assertEquals("candidate-1", explanation.title());
        assertFalse(explanation.sections().isEmpty());

        ExplanationSection transformSection = findSection(explanation, "Transformation");
        assertTrue(hasFact(transformSection, "position", "01"));
        assertTrue(hasFact(transformSection, "before", "x + x"));
        assertTrue(hasFact(transformSection, "after", "2*x"));
        assertTrue(hasFact(transformSection, "rulePath", "factor_out"));
        assertTrue(hasFact(transformSection, "rulePath", "normalize"));

        ExplanationSection reasonSection = findSection(explanation, "Reasons");
        assertTrue(hasFact(reasonSection, "interestReason", "macro reused"));
        assertTrue(hasFact(reasonSection, "pathReason", "oracle agrees"));
        assertTrue(hasFact(reasonSection, "pathReason", "evidence present"));

        ExplanationSection evidenceSection = findSection(explanation, "Evidence");
        assertTrue(hasFact(evidenceSection, "oracle", "AGREE"));
        assertTrue(hasFact(evidenceSection, "ablation", "DEGRADED"));
        assertTrue(hasFact(evidenceSection, "evidencePresent", "true"));
        assertTrue(hasMetric(evidenceSection, "measuredImprovement", 1));
        assertTrue(hasMetric(evidenceSection, "reusedMacros", 1));
    }

    @Test
    void markdownRendererProducesMarkupForExplanation() {
        Explanation explanation = new Explanation("my-candidate", List.of(
            new ExplanationSection("Transformation",
                List.of(new ExplanationFact("before", "a + b"), new ExplanationFact("after", "b + a")),
                List.of(),
                List.of()
            )
        ));

        String markdown = new MarkdownExplanationRenderer().render(explanation);

        assertTrue(markdown.contains("# my-candidate"));
        assertTrue(markdown.contains("## Transformation"));
        assertTrue(markdown.contains("**before:**"));
        assertTrue(markdown.contains("**after:**"));
        assertFalse(markdown.contains("WARNING"));
    }

    @Test
    void plainTextRendererProducesDifferentFormatFromMarkdown() {
        Explanation explanation = new TransformationExplanation(
            "my-candidate",
            "000",
            "a + b",
            "b + a",
            List.of("commute_add"),
            List.of("promotion-eligible: oracle and ablation confirmed"),
            List.of("oracle agrees"),
            "AGREE",
            "DEGRADED",
            true,
            false,
            List.of()
        ).toExplanation();

        String markdown = new MarkdownExplanationRenderer().render(explanation);
        String plainText = new PlainTextExplanationRenderer().render(explanation);

        // Both contain the same data
        assertTrue(plainText.contains("my-candidate"));
        assertTrue(plainText.contains("Transformation"));
        assertTrue(plainText.contains("position: 000"));
        assertTrue(plainText.contains("before: a + b"));
        assertTrue(plainText.contains("after: b + a"));
        assertTrue(plainText.contains("rulePath: commute_add"));
        assertTrue(plainText.contains("pathReason: oracle agrees"));

        // But plainText has no Markdown markup
        assertFalse(plainText.contains("**"));
        assertFalse(plainText.contains("##"));
        assertFalse(plainText.contains("# "));

        // And markdown has markup
        assertTrue(markdown.contains("**"));
        assertTrue(markdown.contains("##"));
    }

    @Test
    void markdownRendererRenderReasonsJoinsWithSemicolon() {
        MarkdownExplanationRenderer renderer = new MarkdownExplanationRenderer();

        assertEquals("a; b; c", renderer.renderReasons(List.of("a", "b", "c"), "fallback"));
        assertEquals("fallback", renderer.renderReasons(List.of(), "fallback"));
        assertEquals("fallback", renderer.renderReasons(null, "fallback"));
    }

    @Test
    void explanationModelHasNoDependencyOnMarkdown() {
        // Build a TransformationExplanation and convert it to Explanation
        // without ever importing or calling any renderer
        TransformationExplanation te = new TransformationExplanation(
            "test", "root", "before", "after",
            List.of("rule1"), List.of("reason1"), List.of("path1"),
            "AGREE", "N/A", false, false, List.of()
        );
        Explanation explanation = te.toExplanation();

        // Verify the data is accessible without any renderer
        assertEquals("test", explanation.title());
        assertEquals(3, explanation.sections().size()); // Transformation + Reasons + Evidence
    }

    @Test
    void explanationModelRetainsPositionAndReasonsInGenericFacts() {
        TransformationExplanation explanation = new TransformationExplanation(
            "candidate",
            "001",
            "x^2",
            "(x + 1)^2 - 1",
            List.of("COMPLETE_SQUARE", "normalize"),
            List.of("promotion-eligible: oracle and ablation confirmed"),
            List.of("oracle agrees", "evidence present"),
            "AGREE",
            "DEGRADED",
            true,
            false,
            List.of()
        );

        Explanation generic = explanation.toExplanation();
        ExplanationSection transformation = findSection(generic, "Transformation");
        ExplanationSection reasons = findSection(generic, "Reasons");

        assertTrue(hasFact(transformation, "position", "001"));
        assertTrue(hasFact(transformation, "before", "x^2"));
        assertTrue(hasFact(transformation, "after", "(x + 1)^2 - 1"));
        assertTrue(hasFact(transformation, "rulePath", "COMPLETE_SQUARE"));
        assertTrue(hasFact(transformation, "rulePath", "normalize"));
        assertTrue(hasFact(reasons, "pathReason", "oracle agrees"));
        assertTrue(hasFact(reasons, "pathReason", "evidence present"));
    }

    @Test
    void explanationModelWorksWithEmptyData() {
        TransformationExplanation empty = new TransformationExplanation(
            null, null, null, null, null, null, null, null, null, false, false, null
        );

        assertEquals("", empty.candidateId());
        assertEquals("", empty.position());
        assertEquals(List.of(), empty.rulePath());
        assertEquals(List.of(), empty.interestReasons());

        // toExplanation() must not throw with empty data
        Explanation explanation = empty.toExplanation();
        assertFalse(explanation.sections().isEmpty());
    }

    private static ExplanationSection findSection(Explanation explanation, String title) {
        return explanation.sections().stream()
            .filter(s -> title.equals(s.title()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Section not found: " + title));
    }

    private static boolean hasFact(ExplanationSection section, String key, String value) {
        return section.facts().stream()
            .anyMatch(f -> key.equals(f.key()) && value.equals(f.value()));
    }

    private static boolean hasMetric(ExplanationSection section, String name, long count) {
        return section.metrics().stream()
            .anyMatch(m -> name.equals(m.name()) && count == m.count());
    }
}
