package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExactRationalPolynomialDecompositionSynthesisOperatorTest {
    private final ExactRationalPolynomialDecompositionSynthesisOperator
        operator =
            new ExactRationalPolynomialDecompositionSynthesisOperator();

    @Test
    void synthesizesDecimalQuarticThroughTheTypedIntegerBoundary() {
        var report = operator.synthesize("0.10*x^4 + 0.40");

        assertTrue(report.generated(), report.detailCode());
        assertEquals("[2/5, 0, 0, 0, 1/10]",
            report.sourcePolynomialMaterial());
        assertTrue(report.contentCertificateHash().matches(
            "sha256:[0-9a-f]{64}"));
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.scalar().equals("1/10")
                && candidate.transformedExpression().contains("1 / 10")
                && candidate.contentCertificateHash().equals(
                    report.contentCertificateHash())
                && candidate.integerCertificateHash().matches(
                    "sha256:[0-9a-f]{64}")
                && candidate.certificateHash().matches(
                    "sha256:[0-9a-f]{64}")));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -2, 2),
                List.of(1, 2, 2))));

        assertFalse(operator.generateCandidates(
            "0.10*x^4 + 0.40").isEmpty());
        assertTrue(operator.generateCandidates(
            "0.10*x^4 + 0.40").stream().allMatch(candidate ->
                candidate.rule().equals(
                    ExactRationalPolynomialDecompositionSynthesisOperator
                        .RULE_ID)
                    && candidate.equivalencePreservingByConstruction()));
    }

    @Test
    void synthesizesASecondQuarticFamilyWithRationalContent() {
        var report = operator.synthesize(
            "(1 / 6)*x^4 + (5 / 6)*x^2 + 2 / 3");

        assertTrue(report.generated(), report.detailCode());
        assertEquals("[2/3, 0, 5/6, 0, 1/6]",
            report.sourcePolynomialMaterial());
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.scalar().equals("1/6")
                && candidate.transformedExpression().contains("1 / 6")));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, 0, 1),
                List.of(1, 0, 4))));
    }

    @Test
    void equivalentDecimalAndFractionSourcesRetainDistinctProvenance() {
        var decimal = operator.synthesize("0.10*x^4 + 0.40");
        var fraction = operator.synthesize("(1 / 10)*x^4 + 2 / 5");

        assertTrue(decimal.generated(), decimal.detailCode());
        assertTrue(fraction.generated(), fraction.detailCode());
        assertEquals(
            decimal.candidates().stream()
                .map(ExactRationalPolynomialDecompositionSynthesisOperator
                    .Candidate::transformedExpression)
                .toList(),
            fraction.candidates().stream()
                .map(ExactRationalPolynomialDecompositionSynthesisOperator
                    .Candidate::transformedExpression)
                .toList());
        assertNotEquals(
            decimal.candidates().getFirst().certificateHash(),
            fraction.candidates().getFirst().certificateHash());
    }

    @Test
    void movesANegativeLeadingSignIntoTheExactScalar() {
        var report = operator.synthesize("-0.10*x^4 - 0.40");

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.scalar().equals("-1/10")
                && candidate.transformedExpression().contains(
                    "0 - 1 / 10")));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
    }

    @Test
    void repeatedRunsAreByteStable() {
        var first = operator.synthesize("0.10*x^4 + 0.40");
        var second = operator.synthesize("0.10*x^4 + 0.40");

        assertEquals(first, second);
    }

    @Test
    void nullAndBlankInputsFailClosed() {
        var nullReport = operator.synthesize(null);
        var blankReport = operator.synthesize("   ");

        assertEquals(
            ExactRationalPolynomialDecompositionSynthesisOperator.Status
                .PARSE_ERROR,
            nullReport.status());
        assertEquals("EXPRESSION_BLANK", nullReport.detailCode());
        assertEquals(
            ExactRationalPolynomialDecompositionSynthesisOperator.Status
                .PARSE_ERROR,
            blankReport.status());
        assertEquals("EXPRESSION_BLANK", blankReport.detailCode());
        assertTrue(operator.generateCandidates(null).isEmpty());
        assertTrue(operator.generateCandidates("   ").isEmpty());
    }

    @Test
    void rejectsUnsupportedAndUnfactorableInputsFailClosed() {
        assertEquals(
            ExactRationalPolynomialDecompositionSynthesisOperator.Status
                .PARSE_ERROR,
            operator.synthesize(".5*x^4 + 2").status());
        assertEquals(
            ExactRationalPolynomialDecompositionSynthesisOperator.Status
                .UNSUPPORTED_EXACT_POLYNOMIAL,
            operator.synthesize("x^4 + y").status());
        assertEquals(
            ExactRationalPolynomialDecompositionSynthesisOperator.Status
                .NOT_UNIVARIATE_QUARTIC,
            operator.synthesize("x^5 + 1").status());
        assertEquals(
            ExactRationalPolynomialDecompositionSynthesisOperator.Status
                .INTEGER_SYNTHESIS_FAILED,
            operator.synthesize("0.10*x^4 + 0.20").status());
    }

    private static boolean factorPair(
        ExactRationalPolynomialDecompositionSynthesisOperator.Candidate
            candidate,
        List<Integer> expectedLeft,
        List<Integer> expectedRight
    ) {
        List<BigInteger> left = expectedLeft.stream()
            .map(BigInteger::valueOf)
            .toList();
        List<BigInteger> right = expectedRight.stream()
            .map(BigInteger::valueOf)
            .toList();
        return candidate.leftCoefficients().equals(left)
            && candidate.rightCoefficients().equals(right);
    }
}
