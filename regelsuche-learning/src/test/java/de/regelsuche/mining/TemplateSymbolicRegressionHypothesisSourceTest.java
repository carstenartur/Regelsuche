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

    @Test
    void fitsQuadraticCubicRationalAndGeometricTemplates() {
        TemplateSymbolicRegressionHypothesisSource source = new TemplateSymbolicRegressionHypothesisSource(true, 4);

        List<HypothesisCandidate> quadratic = source.propose(List.of(
            path("q1", "0", "1"),
            path("q2", "1", "4"),
            path("q3", "2", "9"),
            path("q4", "3", "16")
        ));
        assertTrue(quadratic.stream().anyMatch(hypothesis ->
            hypothesis.assumptions().contains("template:polynomial-degree-2")
                && hypothesis.rightPattern().equals("x^2 + 2 * x + 1")));

        List<HypothesisCandidate> cubic = source.propose(List.of(
            path("c1", "0", "0"),
            path("c2", "1", "1"),
            path("c3", "2", "8"),
            path("c4", "3", "27")
        ));
        assertTrue(cubic.stream().anyMatch(hypothesis ->
            hypothesis.assumptions().contains("template:polynomial-degree-3")
                && hypothesis.rightPattern().equals("x^3")));

        List<HypothesisCandidate> rational = source.propose(List.of(
            path("r1", "1", "3"),
            path("r2", "2", "2"),
            path("r3", "5", "1"),
            path("r4", "11", "0.5")
        ));
        assertTrue(rational.stream().anyMatch(hypothesis ->
            hypothesis.assumptions().contains("template:rational-reciprocal-shift")
                && hypothesis.rightPattern().equals("6 / (x + 1)")));

        List<HypothesisCandidate> geometric = source.propose(List.of(
            path("g1", "0", "2"),
            path("g2", "1", "6"),
            path("g3", "2", "18"),
            path("g4", "3", "54")
        ));
        assertTrue(geometric.stream().anyMatch(hypothesis ->
            hypothesis.assumptions().contains("template:geometric-sequence")
                && hypothesis.rightPattern().equals("2 * 3^x")));
    }

    @Test
    void annotatesResidualAndConfidenceForRanking() {
        TemplateSymbolicRegressionHypothesisSource source = new TemplateSymbolicRegressionHypothesisSource(true, 3);

        HypothesisCandidate hypothesis = source.propose(List.of(
            path("p1", "1", "2"),
            path("p2", "2", "4"),
            path("p3", "3", "6")
        )).stream()
            .filter(candidate -> candidate.rightPattern().equals("2 * x"))
            .findFirst()
            .orElseThrow();

        assertTrue(hypothesis.parameterRelations().contains("max-residual=0"));
        assertTrue(hypothesis.parameterRelations().contains("confidence=1"));
        assertTrue(hypothesis.expressionPlaceholders().get("symbolicRegression").contains("confidence=1"));
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
