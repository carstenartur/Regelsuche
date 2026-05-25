package de.regelsuche.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExplanationServiceTest {

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
        ExpressionScore before = new ExpressionScore(8, 5, 2, 2, 0);
        ExpressionScore after = new ExpressionScore(10, 7, 3, 2, 0);
        return new DiscoveredTransformation(
            "tid",
            "a*(b + c)",
            "a*b + a*c",
            List.of(step),
            before,
            after,
            -2,
            CandidateProofStatus.VALIDATED_BY_EXAMPLES,
            Instant.EPOCH,
            "hash"
        );
    }

    @Test
    void schoolFormHasGermanLabels() {
        String rendered = new ExplanationService().renderPath(samplePath(), ExplanationService.Form.SCHOOL);
        assertTrue(rendered.contains("Regel: Distributivgesetz"));
        assertTrue(rendered.contains("Vorher: a*(b + c)"));
        assertTrue(rendered.contains("Status: Äquivalenz erhaltend."));
    }

    @Test
    void shortFormIsSingleLinePerStep() {
        String rendered = new ExplanationService().renderPath(samplePath(), ExplanationService.Form.SHORT);
        assertEquals("1. a*(b + c) = a*b + a*c", rendered);
    }

    @Test
    void latexUsesCdotAndRightarrow() {
        String rendered = new ExplanationService().renderPath(samplePath(), ExplanationService.Form.LATEX);
        assertTrue(rendered.contains("\\cdot"));
        assertTrue(rendered.contains("\\rightarrow"));
    }

    @Test
    void jsonFormContainsRuleId() {
        String rendered = new ExplanationService().renderPath(samplePath(), ExplanationService.Form.JSON);
        assertTrue(rendered.contains("\"ruleId\":\"ast_distribute_left_add\""));
    }
}
