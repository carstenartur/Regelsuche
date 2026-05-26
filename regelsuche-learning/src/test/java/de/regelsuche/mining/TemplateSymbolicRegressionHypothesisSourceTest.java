package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateSymbolicRegressionHypothesisSourceTest {
    @Test
    void fitsTemplateLibraryFromNumericSamplesAsEvidenceOnlyHypotheses() {
        TemplateSymbolicRegressionHypothesisSource source = new TemplateSymbolicRegressionHypothesisSource(true, 3);

        List<HypothesisCandidate> hypotheses = source.propose(List.of(
            path("p1", "1", "2"),
            path("p2", "2", "4"),
            path("p3", "3", "6")
        ));

        assertFalse(hypotheses.isEmpty());
        assertTrue(hypotheses.stream().anyMatch(hypothesis -> hypothesis.rightPattern().equals("2 * x")));
        assertTrue(hypotheses.stream().allMatch(hypothesis -> hypothesis.proofStatus() == CandidateProofStatus.OBSERVED));
        assertTrue(hypotheses.stream().allMatch(hypothesis -> hypothesis.assumptions().contains("symbolic-regression-evidence-only")));
        assertTrue(hypotheses.stream().noneMatch(hypothesis ->
            hypothesis.proofStatus().atLeast(CandidateProofStatus.SYMBOLICALLY_VERIFIED)));
    }

    @Test
    void ignoresNonNumericSamplesAndMinimumSupportGaps() {
        TemplateSymbolicRegressionHypothesisSource source = new TemplateSymbolicRegressionHypothesisSource(true, 3);

        List<HypothesisCandidate> hypotheses = source.propose(List.of(
            path("p1", "x", "x + 1"),
            path("p2", "1", "2")
        ));

        assertEquals(List.of(), hypotheses);
    }

    private static SuccessfulTransformationPath path(String id, String left, String right) {
        return new SuccessfulTransformationPath(
            id,
            left,
            right,
            List.of(left, right),
            List.of("sample"),
            new ExpressionScore(2, 0, 0, 0, 0),
            new ExpressionScore(1, 0, 0, 0, 0),
            true,
            "sample",
            Map.of(),
            List.of()
        );
    }
}
