package de.regelsuche.moves.hypothesis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.moves.RewriteMoveKind;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MathematicalParameterIntelligenceTest {

    private final MathematicalParameterIntelligence intelligence = new MathematicalParameterIntelligence();

    @Test
    void completeSquareWorksWithAtomX() {
        assertCompleteSquare("x^2 + 6*x + 5", "x", "3", "-4");
    }

    @Test
    void completeSquareWorksWithAtomSum() {
        assertCompleteSquare("(a + b)^2 + 6*(a + b) + 5", "a + b", "3", "-4");
    }

    @Test
    void completeSquareWorksWithAtomTrigSum() {
        assertCompleteSquare(
                "(sin(x) + cos(x))^2 + 2*(sin(x) + cos(x)) + 1", "sin(x) + cos(x)", "1", "0");
    }

    private void assertCompleteSquare(String input, String atom, String shift, String residue) {
        var report = intelligence.analyse(input);
        List<ParameterHypothesis> square = report.bySource(HypothesisSource.COMPLETE_SQUARE);
        assertTrue(
                square.stream().anyMatch(h -> h.parameterName().equals("shift") && h.value().equals(shift)),
                "expected shift=" + shift + " in " + square);
        assertTrue(
                square.stream().anyMatch(h -> h.parameterName().equals("residue") && h.value().equals(residue)),
                "expected residue=" + residue + " in " + square);
        assertTrue(
                report.bySource(HypothesisSource.SKELETON_MATCH).stream()
                        .anyMatch(h -> h.value().equals(atom)),
                "expected skeleton atom " + atom + " in " + report.bySource(HypothesisSource.SKELETON_MATCH));
    }

    @Test
    void commonFactorFindsSimpleFactor() {
        var report = intelligence.analyse("x*(y + 1) + z*(y + 1)");
        assertTrue(
                report.bySource(HypothesisSource.COMMON_FACTOR).stream()
                        .anyMatch(h -> h.value().equals("y + 1")),
                report.bySource(HypothesisSource.COMMON_FACTOR).toString());
    }

    @Test
    void commonFactorFindsComplexFactor() {
        var report = intelligence.analyse("p*((a + b)^2 + 1) + q*((a + b)^2 + 1)");
        assertTrue(
                report.bySource(HypothesisSource.COMMON_FACTOR).stream()
                        .anyMatch(h -> h.value().equals("(a + b) ^ 2 + 1")),
                report.bySource(HypothesisSource.COMMON_FACTOR).toString());
    }

    @Test
    void cancellationFindsPlusOneForXMinusOneEqualsZero() {
        var report = intelligence.analyse("x - 1 = 0");
        assertTrue(
                report.bySource(HypothesisSource.CANCELLATION).stream()
                        .anyMatch(h -> h.value().equals("+1")),
                report.bySource(HypothesisSource.CANCELLATION).toString());
    }

    @Test
    void cancellationFindsPlusTForXMinusTEqualsZero() {
        var report = intelligence.analyse("x - T = 0");
        assertTrue(
                report.bySource(HypothesisSource.CANCELLATION).stream()
                        .anyMatch(h -> h.value().equals("+T")),
                report.bySource(HypothesisSource.CANCELLATION).toString());
    }

    @Test
    void repeatedSubtreeFindsSubstitutionAtom() {
        var report = intelligence.analyse("(sin(x) + cos(x))^2 + 2*(sin(x) + cos(x)) + 1");
        assertTrue(
                report.bySource(HypothesisSource.REPEATED_SUBTREE).stream()
                        .anyMatch(h -> h.value().equals("sin(x) + cos(x)")),
                report.bySource(HypothesisSource.REPEATED_SUBTREE).toString());
    }

    @Test
    void targetGuidedHypothesisRecognisesTargetStructure() {
        var report = intelligence.analyse("x", "x + 1");
        assertTrue(
                report.bySource(HypothesisSource.TARGET_DIFF).stream()
                        .anyMatch(h -> h.value().equals("+1")),
                report.bySource(HypothesisSource.TARGET_DIFF).toString());
    }

    @Test
    void equationIsolationProposesAdditiveInverse() {
        var report = intelligence.analyse("x - T = 0");
        assertTrue(
                report.bySource(HypothesisSource.EQUATION_ISOLATION).stream()
                        .anyMatch(h -> h.value().equals("+T")),
                report.bySource(HypothesisSource.EQUATION_ISOLATION).toString());
    }

    @Test
    void allHypothesesAreDeterministicallySorted() {
        var first = intelligence.analyse("x^2 + 6*x + 5", "x + 1").hypotheses();
        var second = intelligence.analyse("x^2 + 6*x + 5", "x + 1").hypotheses();
        assertEquals(first, second);

        List<ParameterHypothesis> sorted = first.stream()
                .sorted(ParameterHypothesis.CANONICAL_ORDER)
                .toList();
        assertEquals(sorted, first);
    }

    @Test
    void searchSpaceIntelligenceShowsParameterSources() {
        var report = intelligence.analyse("x^2 + 6*x + 5");
        assertFalse(report.sourceHistogram().isEmpty(), "expected non-empty source histogram");
        assertTrue(report.sources().contains(HypothesisSource.COMPLETE_SQUARE.name()),
                report.sourceHistogram().toString());
        // Every hypothesis is attributable to a known source.
        assertTrue(report.hypotheses().stream().allMatch(h -> h.source() != null));
    }

    @Test
    void noHypothesesProducedWhenMoveKindsDisallowed() {
        var context = ParameterContext.of("x^2 + 6*x + 5", null, 32, java.util.Set.of(RewriteMoveKind.NORMALIZE));
        assertTrue(intelligence.analyse(context).hypotheses().isEmpty());
    }

    @Test
    void skeletonPlaceholdersDoNotCollideWithExistingAtoms() {
        var context = ParameterContext.of("A^2 + 6*A + 5");
        Set<String> atomCanonicals = context.skeletons().stream()
                .map(TermSkeleton::atomCanonical)
                .collect(Collectors.toSet());
        assertTrue(context.skeletons().stream().noneMatch(s -> atomCanonicals.contains(s.placeholder())));
    }
}
