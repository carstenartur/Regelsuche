package de.regelsuche.explain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.PathReplayDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.export.layout.AstAriaRenderer;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Verifies that ARIA descriptions and replay explanations are fully
 * internationalisable (German / English).
 *
 * <p>Tests correspond to acceptance criteria listed in the issue:
 * {@code localizedReplayExplanationGerman},
 * {@code localizedReplayExplanationEnglish},
 * {@code mathReplayContainsAriaDescriptions},
 * {@code screenreaderReplayNarrationWorks}.</p>
 */
class LocalizedExplanationTest {

    private static ExpressionScore score(int total) {
        return new ExpressionScore(total, total, total, 0, 0);
    }

    private static DiscoveredTransformation samplePath() {
        TransformationStep step = new TransformationStep(
            0,
            "a*(b + c)",
            "a*b + a*c",
            "ast_distribute_left_add",
            RewriteKind.EXPAND,
            10,
            12,
            true,
            "Distributivgesetz angewandt"
        );
        return new DiscoveredTransformation(
            "tid",
            "a*(b + c)",
            "a*b + a*c",
            List.of(step),
            score(10),
            score(12),
            -2,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Instant.EPOCH,
            "hash"
        );
    }

    // ── ExplanationService locale tests ──────────────────────────────────────

    @Test
    void localizedReplayExplanationGerman() {
        ExplanationService svc = new ExplanationService();
        DiscoveredTransformation path = samplePath();
        String german = svc.renderPath(path, ExplanationService.Form.SCHOOL, Locale.GERMAN);
        assertTrue(german.contains("Regel:"), "German output must use 'Regel:'");
        assertTrue(german.contains("Vorher:"), "German output must use 'Vorher:'");
        assertTrue(german.contains("Nachher:"), "German output must use 'Nachher:'");
        assertTrue(german.contains("Distributivgesetz"), "German output must name the rule");
        assertTrue(german.contains("Äquivalenz erhaltend"), "German output must state equivalence");
    }

    @Test
    void localizedReplayExplanationEnglish() {
        ExplanationService svc = new ExplanationService();
        DiscoveredTransformation path = samplePath();
        String english = svc.renderPath(path, ExplanationService.Form.SCHOOL, Locale.ENGLISH);
        assertTrue(english.contains("Rule:"), "English output must use 'Rule:'");
        assertTrue(english.contains("Before:"), "English output must use 'Before:'");
        assertTrue(english.contains("After:"), "English output must use 'After:'");
        assertTrue(english.contains("Distributive law"), "English output must name the rule");
        assertTrue(english.contains("Equivalence-preserving"), "English output must state equivalence");
    }

    @Test
    void defaultLocaleIsGerman() {
        ExplanationService svc = new ExplanationService();
        DiscoveredTransformation path = samplePath();
        // No locale argument → must produce German output (backward compat).
        String defaultOutput = svc.renderPath(path, ExplanationService.Form.SCHOOL);
        assertTrue(defaultOutput.contains("Regel:"), "Default locale must produce German output");
    }

    @Test
    void englishStepHeadlineUsesStep() {
        ExplanationService svc = new ExplanationService();
        DiscoveredTransformation path = samplePath();
        String english = svc.renderPath(path, ExplanationService.Form.SCHOOL, Locale.ENGLISH);
        assertTrue(english.startsWith("Step 1:"), "English path must start with 'Step 1:'");
    }

    @Test
    void germanStepHeadlineUsesSchritt() {
        ExplanationService svc = new ExplanationService();
        DiscoveredTransformation path = samplePath();
        String german = svc.renderPath(path, ExplanationService.Form.SCHOOL, Locale.GERMAN);
        assertTrue(german.startsWith("Schritt 1:"), "German path must start with 'Schritt 1:'");
    }

    // ── AstAriaRenderer locale tests ─────────────────────────────────────────

    @Test
    void ariaLabelGermanUsesGermanWords() {
        String label = AstAriaRenderer.ariaLabel("a*b + c/d", Locale.GERMAN);
        assertTrue(label.contains("mal"), "German aria must contain 'mal' for *");
        assertTrue(label.contains("geteilt durch"), "German aria must contain 'geteilt durch' for /");
        assertTrue(label.contains("plus"), "German aria must contain 'plus' for +");
    }

    @Test
    void ariaLabelEnglishUsesEnglishWords() {
        String label = AstAriaRenderer.ariaLabel("a*b + c/d", Locale.ENGLISH);
        assertTrue(label.contains("times"), "English aria must contain 'times' for *");
        assertTrue(label.contains("divided by"), "English aria must contain 'divided by' for /");
        assertTrue(label.contains("plus"), "English aria must contain 'plus' for +");
    }

    @Test
    void ariaLabelDefaultIsDeutsch() {
        // No locale → backward compat → German
        String defaultLabel = AstAriaRenderer.ariaLabel("x > 0");
        assertTrue(defaultLabel.contains("grösser"), "Default aria must use German 'grösser' for >");
        assertFalse(defaultLabel.contains("greater than"), "Default aria must not use English 'greater than'");
    }

    @Test
    void ariaLabelEnglishInequalityWords() {
        String label = AstAriaRenderer.ariaLabel("x < y", Locale.ENGLISH);
        assertTrue(label.contains("less than"), "English aria must contain 'less than' for <");
        String label2 = AstAriaRenderer.ariaLabel("x > y", Locale.ENGLISH);
        assertTrue(label2.contains("greater than"), "English aria must contain 'greater than' for >");
    }

    // ── mathReplayContainsAriaDescriptions ───────────────────────────────────

    @Test
    void mathReplayContainsAriaDescriptions() {
        PathReplayDto dto = PathReplayDto.from(samplePath(), new ExplanationService());
        for (PathReplayDto.ReplayStep step : dto.steps()) {
            de.regelsuche.export.layout.MathLayout layout = step.layout();
            assertFalse(layout.ariaLabel().isBlank(),
                "Every ReplayStep layout must carry a non-blank aria-label for screen readers");
        }
    }

    // ── screenreaderReplayNarrationWorks ─────────────────────────────────────

    @Test
    void screenreaderReplayNarrationWorksGerman() {
        AriaDescriptionService aria = new AriaDescriptionService();
        DiscoveredTransformation path = samplePath();
        TransformationStep step = path.steps().get(0);
        String narration = aria.stepNarration(step, 0, Locale.GERMAN);
        assertTrue(narration.contains("Schritt 1"), "German narration must start with 'Schritt 1'");
        assertTrue(narration.contains("Distributivgesetz"), "German narration must name the rule");
        assertTrue(narration.contains("Vorher:"), "German narration must describe before expression");
        assertTrue(narration.contains("Nachher:"), "German narration must describe after expression");
    }

    @Test
    void screenreaderReplayNarrationWorksEnglish() {
        AriaDescriptionService aria = new AriaDescriptionService();
        DiscoveredTransformation path = samplePath();
        TransformationStep step = path.steps().get(0);
        String narration = aria.stepNarration(step, 0, Locale.ENGLISH);
        assertTrue(narration.contains("Step 1"), "English narration must start with 'Step 1'");
        assertTrue(narration.contains("Distributive law"), "English narration must name the rule");
        assertTrue(narration.contains("Before:"), "English narration must describe before expression");
        assertTrue(narration.contains("After:"), "English narration must describe after expression");
    }

    @Test
    void comparatorFlipWarningIsLocalized() {
        AriaDescriptionService aria = new AriaDescriptionService();
        String de = aria.comparatorFlipWarning(Locale.GERMAN);
        String en = aria.comparatorFlipWarning(Locale.ENGLISH);
        assertTrue(de.contains("Vergleichszeichen"), "German warning must mention comparator");
        assertTrue(en.contains("comparator"), "English warning must mention comparator");
        assertFalse(de.equals(en), "German and English warnings must differ");
    }

    @Test
    void pathNarrationContainsAllStepsAndExpressions() {
        AriaDescriptionService aria = new AriaDescriptionService();
        DiscoveredTransformation path = samplePath();
        String narration = aria.pathNarration(path, Locale.ENGLISH);
        assertTrue(narration.contains("Step 1"), "Path narration must include Step 1");
        assertTrue(narration.contains("Original expression"), "Path narration must mention original expression");
        assertTrue(narration.contains("Result"), "Path narration must mention result");
    }
}
