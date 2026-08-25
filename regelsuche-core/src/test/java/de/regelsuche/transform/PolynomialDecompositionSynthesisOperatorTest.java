package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.polynomial.FactorizationVerifier;
import de.regelsuche.polynomial.SparsePolynomial;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialDecompositionSynthesisOperatorTest {
    private final PolynomialDecompositionSynthesisOperator operator =
        new PolynomialDecompositionSynthesisOperator();

    @Test
    void semanticViewUsesCompleteSubtreesAsPolynomialAtoms() {
        PolynomialSemanticView.Analysis analysis =
            new PolynomialSemanticView().analyze(
                "(x + 1)^4 + 5*(x + 1)^2*sin(t)^2 + 4*sin(t)^4");

        assertTrue(analysis.supported(), analysis.detailCode());
        PolynomialSemanticView.PolynomialView view = analysis.view();
        assertEquals(2, view.atoms().size());
        assertEquals(
            List.of("sin(t)", "x + 1"),
            view.atoms().stream()
                .map(PolynomialSemanticView.StructuralAtom::display)
                .toList());
        assertTrue(view.polynomial().isHomogeneousOfDegree(4));
        assertEquals(
            BigInteger.ONE,
            view.polynomial().coefficient(0, 4));
        assertEquals(
            BigInteger.valueOf(5),
            view.polynomial().coefficient(2, 2));
        assertEquals(
            BigInteger.valueOf(4),
            view.polynomial().coefficient(4, 0));
    }

    @Test
    void exactSourceCoefficientsNeverRoundTripThroughDouble() {
        PolynomialSemanticView.Analysis analysis =
            new PolynomialSemanticView().analyze(
                "9007199254740993*x^4 + 2.0");

        assertTrue(analysis.supported(), analysis.detailCode());
        assertEquals(
            new BigInteger("9007199254740993"),
            analysis.view().polynomial().coefficient(4));
        assertEquals(
            BigInteger.TWO,
            analysis.view().polynomial().coefficient(0));
        assertEquals(
            PolynomialSemanticView.Status.UNSUPPORTED,
            new PolynomialSemanticView().analyze("0.10*x^4").status());
    }

    @Test
    void synthesizesSophieGermainFromTheFactorizationEngine() {
        ExpressionFactorizationReport report =
            operator.factorExpression("x^4 + 4*y^4");

        assertTrue(report.generated(), report.detailCode());
        assertEquals(
            FactorizationVerifier.ClaimStrength.VERIFIED_DECOMPOSITION,
            report.claimStrength());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(
                candidate.factorization(),
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.factorization().verificationCertificateHash()
                .matches("sha256:[0-9a-f]{64}")));
        assertTrue(report.verificationHash().matches(
            "sha256:[0-9a-f]{64}"));
        assertTrue(operator.generateCandidates("x^4 + 4*y^4").stream()
            .allMatch(candidate ->
                candidate.rule().equals(
                    PolynomialDecompositionSynthesisOperator.RULE_ID)
                && candidate.equivalencePreservingByConstruction()));
    }

    @Test
    void homogenizesUnivariateQuarticsWithTheStructuralUnitAtom() {
        ExpressionFactorizationReport report =
            operator.factorExpression("x^4 + 4");

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(
                candidate.factorization(),
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            candidate.transformedExpression().contains("(x) ^ 2")
                && !candidate.transformedExpression().contains("(1) ^ 2")));
    }

    @Test
    void synthesizesOtherQuarticFamiliesWithoutNamedIdentityRules() {
        ExpressionFactorizationReport even = operator.factorExpression(
            "x^4 + 5*x^2*y^2 + 4*y^4");
        ExpressionFactorizationReport cyclotomic =
            operator.factorExpression("x^4 + x^2*y^2 + y^4");

        assertTrue(even.generated(), even.detailCode());
        assertTrue(even.candidates().stream().anyMatch(candidate ->
            factorPair(
                candidate.factorization(),
                List.of(1, 0, 1),
                List.of(1, 0, 4))));
        assertTrue(cyclotomic.generated(), cyclotomic.detailCode());
        assertTrue(cyclotomic.candidates().stream().anyMatch(candidate ->
            factorPair(
                candidate.factorization(),
                List.of(1, -1, 1),
                List.of(1, 1, 1))));
    }

    @Test
    void arbitraryAstSubstitutionsReuseTheSameSolvedSchema() {
        ExpressionFactorizationReport report = operator.factorExpression(
            "sin(t)^4 + 4*(x + 1)^4");

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(
                candidate.factorization(),
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            candidate.transformedExpression().contains("x + 1")
                && candidate.transformedExpression().contains("sin(t)")));
    }

    @Test
    void templateMissUnsupportedDomainAndZeroRemainDistinct() {
        ExpressionFactorizationReport miss = operator.factorExpression(
            "x^4 + x^2*y^2 + 2*y^4");
        ExpressionFactorizationReport unsupported =
            operator.factorExpression("x^4 + 1/y");
        ExpressionFactorizationReport wrongShape =
            operator.factorExpression("x^4 + 4*y^4 + 1");
        ExpressionFactorizationReport zero =
            operator.factorExpression("x - x");

        assertEquals(
            ExpressionFactorizationReport.Status.NO_FACTORIZATION_FOUND,
            miss.status());
        assertEquals(
            ExpressionFactorizationReport.Status
                .UNSUPPORTED_SEMANTIC_VIEW,
            unsupported.status());
        assertEquals(
            ExpressionFactorizationReport.Status
                .UNSUPPORTED_FACTORIZATION_REQUEST,
            wrongShape.status());
        assertEquals(
            ExpressionFactorizationReport.Status.NO_FACTORIZATION_FOUND,
            zero.status());
    }

    @Test
    void candidateOrderWorkAndCertificatesAreDeterministic() {
        ExpressionFactorizationReport first =
            operator.factorExpression("x^4 + 4*y^4");
        ExpressionFactorizationReport second =
            operator.factorExpression("x^4 + 4*y^4");

        assertEquals(first, second);
        assertFalse(first.candidates().isEmpty());
        assertTrue(first.totalWorkUnits() > 0);
        assertTrue(first.work().units("engine.divisor-tests") > 0);
        assertTrue(first.work().units("verify.product-comparisons") > 0);
    }

    private static boolean factorPair(
        FactorizationVerifier.VerifiedCandidate<BigInteger> candidate,
        List<Integer> expectedLeft,
        List<Integer> expectedRight
    ) {
        if (candidate.factors().size() != 2) {
            return false;
        }
        SparsePolynomial<BigInteger> first =
            candidate.factors().getFirst().polynomial();
        SparsePolynomial<BigInteger> second =
            candidate.factors().get(1).polynomial();
        return matches(first, expectedLeft)
                && matches(second, expectedRight)
            || matches(first, expectedRight)
                && matches(second, expectedLeft);
    }

    private static boolean matches(
        SparsePolynomial<BigInteger> factor,
        List<Integer> expected
    ) {
        return factor.coefficient(2, 0).equals(
                BigInteger.valueOf(expected.get(0)))
            && factor.coefficient(1, 1).equals(
                BigInteger.valueOf(expected.get(1)))
            && factor.coefficient(0, 2).equals(
                BigInteger.valueOf(expected.get(2)));
    }
}
