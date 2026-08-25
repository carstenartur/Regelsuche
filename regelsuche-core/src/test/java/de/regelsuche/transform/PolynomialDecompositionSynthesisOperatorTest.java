package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.NumberExpr;
import de.regelsuche.ast.VariableExpr;
import java.math.BigInteger;
import java.util.List;
import java.util.TreeMap;
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
        PolynomialSemanticView.Polynomial polynomial = analysis.polynomial();
        assertEquals(2, polynomial.atoms().size());
        assertEquals(List.of("sin(t)", "x + 1"),
            polynomial.atoms().stream()
                .map(PolynomialSemanticView.Atom::display)
                .toList());
        assertTrue(polynomial.isHomogeneousOfDegree(4));
        assertEquals(BigInteger.ONE, polynomial.coefficient(0, 4));
        assertEquals(BigInteger.valueOf(5), polynomial.coefficient(2, 2));
        assertEquals(BigInteger.valueOf(4), polynomial.coefficient(4, 0));
    }

    @Test
    void synthesizesSophieGermainFromCoefficientConstraints() {
        PolynomialDecompositionSynthesisOperator.SynthesisReport report =
            operator.synthesize("x^4 + 4*y^4");

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.certificateHash().matches("sha256:[0-9a-f]{64}")));
        assertTrue(operator.generateCandidates("x^4 + 4*y^4").stream()
            .allMatch(candidate ->
                candidate.rule().equals(
                    PolynomialDecompositionSynthesisOperator.RULE_ID)
                && candidate.equivalencePreservingByConstruction()));
    }

    @Test
    void typedPolynomialEntryPointDoesNotRenderAndReparse() {
        TreeMap<PolynomialSemanticView.Monomial, BigInteger> coefficients =
            univariateQuarticCoefficients();
        PolynomialSemanticView.Polynomial typed =
            new PolynomialSemanticView.Polynomial(
                PolynomialSemanticView.VIEW_ID,
                List.of(new PolynomialSemanticView.Atom(
                    "typed-only:x",
                    "<typed-x>",
                    new VariableExpr("x"))),
                coefficients,
                4,
                false,
                0);

        PolynomialDecompositionSynthesisOperator.SynthesisReport report =
            operator.synthesize(typed);

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.sourcePolynomialMaterial().contains("<typed-x>"));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
        assertTrue(report.candidates().stream().allMatch(candidate ->
            candidate.transformedExpression().contains("x")
                && !candidate.transformedExpression().contains("typed-x")));
    }

    @Test
    void typedEntryPointRevalidatesPublicPolynomialObjects() {
        PolynomialSemanticView.Polynomial forgedMetadata =
            new PolynomialSemanticView.Polynomial(
                PolynomialSemanticView.VIEW_ID,
                List.of(
                    atom("x", new VariableExpr("x")),
                    atom("y", new VariableExpr("y"))),
                coefficientsWithLowerDegreeTerm(),
                4,
                true,
                0);
        PolynomialSemanticView.Polynomial duplicateAtomKeys =
            new PolynomialSemanticView.Polynomial(
                PolynomialSemanticView.VIEW_ID,
                List.of(
                    atom("same", new VariableExpr("x")),
                    atom("same", new VariableExpr("y"))),
                binaryQuarticCoefficients(),
                4,
                true,
                0);
        PolynomialSemanticView.Polynomial forgedStructuralUnit =
            new PolynomialSemanticView.Polynomial(
                PolynomialSemanticView.VIEW_ID,
                List.of(
                    atom("x", new VariableExpr("x")),
                    new PolynomialSemanticView.Atom(
                        "structural-unit:1",
                        "1",
                        new VariableExpr("notOne"))),
                binaryQuarticCoefficients(),
                4,
                true,
                0);

        assertTypedFailure(
            forgedMetadata,
            "TYPED_POLYNOMIAL_METADATA_INCONSISTENT");
        assertTypedFailure(
            duplicateAtomKeys,
            "TYPED_POLYNOMIAL_ATOM_KEYS_NOT_UNIQUE");
        assertTypedFailure(
            forgedStructuralUnit,
            "TYPED_POLYNOMIAL_STRUCTURAL_UNIT_INVALID");
    }

    @Test
    void typedEntryAcceptsAnAuthenticPreHomogenizedStructuralUnit() {
        PolynomialSemanticView.Polynomial typed =
            new PolynomialSemanticView.Polynomial(
                PolynomialSemanticView.VIEW_ID,
                List.of(
                    atom("x", new VariableExpr("x")),
                    new PolynomialSemanticView.Atom(
                        "structural-unit:1",
                        "1",
                        new NumberExpr(1))),
                binaryQuarticCoefficients(),
                4,
                true,
                0);

        PolynomialDecompositionSynthesisOperator.SynthesisReport report =
            operator.synthesize(typed);

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
    }

    @Test
    void homogenizesUnivariateQuarticsWithTheStructuralUnitAtom() {
        PolynomialDecompositionSynthesisOperator.SynthesisReport report =
            operator.synthesize("x^4 + 4");

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            candidate.transformedExpression().contains("x ^ 2")
                && !candidate.transformedExpression().contains("1 ^ 2")));
    }

    @Test
    void synthesizesOtherQuarticFamiliesWithoutNamedIdentityRules() {
        PolynomialDecompositionSynthesisOperator.SynthesisReport even =
            operator.synthesize("x^4 + 5*x^2*y^2 + 4*y^4");
        PolynomialDecompositionSynthesisOperator.SynthesisReport cyclotomic =
            operator.synthesize("x^4 + x^2*y^2 + y^4");

        assertTrue(even.generated(), even.detailCode());
        assertTrue(even.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, 0, 1),
                List.of(1, 0, 4))));
        assertTrue(cyclotomic.generated(), cyclotomic.detailCode());
        assertTrue(cyclotomic.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -1, 1),
                List.of(1, 1, 1))));
    }

    @Test
    void arbitraryAstSubstitutionsReuseTheSameSolvedSchema() {
        PolynomialDecompositionSynthesisOperator.SynthesisReport report =
            operator.synthesize("sin(t)^4 + 4*(x + 1)^4");

        assertTrue(report.generated(), report.detailCode());
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            factorPair(candidate,
                List.of(1, -2, 2),
                List.of(1, 2, 2))));
        assertTrue(report.candidates().stream().anyMatch(candidate ->
            candidate.transformedExpression().contains("x + 1")
                && candidate.transformedExpression().contains("sin(t)")));
    }

    @Test
    void rejectsUnsupportedAndIrreducibleInputsFailClosed() {
        assertEquals(
            PolynomialDecompositionSynthesisOperator.Status
                .NO_INTEGER_QUADRATIC_FACTORIZATION,
            operator.synthesize("x^4 + x^2*y^2 + 2*y^4").status());
        assertEquals(
            PolynomialDecompositionSynthesisOperator.Status
                .UNSUPPORTED_SEMANTIC_VIEW,
            operator.synthesize("x^4 + 1/y").status());
        assertEquals(
            PolynomialDecompositionSynthesisOperator.Status
                .NOT_BINARY_HOMOGENEOUS_QUARTIC,
            operator.synthesize("x^4 + 4*y^4 + 1").status());
    }

    @Test
    void candidateOrderAndCertificatesAreDeterministic() {
        PolynomialDecompositionSynthesisOperator.SynthesisReport first =
            operator.synthesize("x^4 + 4*y^4");
        PolynomialDecompositionSynthesisOperator.SynthesisReport second =
            operator.synthesize("x^4 + 4*y^4");

        assertEquals(first, second);
        assertFalse(first.candidates().isEmpty());
    }

    private void assertTypedFailure(
        PolynomialSemanticView.Polynomial polynomial,
        String detailCode
    ) {
        PolynomialDecompositionSynthesisOperator.SynthesisReport report =
            operator.synthesize(polynomial);
        assertEquals(
            PolynomialDecompositionSynthesisOperator.Status
                .UNSUPPORTED_SEMANTIC_VIEW,
            report.status());
        assertEquals(detailCode, report.detailCode());
        assertTrue(report.candidates().isEmpty());
    }

    private static PolynomialSemanticView.Atom atom(
        String key,
        VariableExpr expression
    ) {
        return new PolynomialSemanticView.Atom(
            key,
            expression.name(),
            expression);
    }

    private static TreeMap<PolynomialSemanticView.Monomial, BigInteger>
            univariateQuarticCoefficients() {
        TreeMap<PolynomialSemanticView.Monomial, BigInteger> coefficients =
            new TreeMap<>();
        coefficients.put(
            new PolynomialSemanticView.Monomial(List.of(4)),
            BigInteger.ONE);
        coefficients.put(
            new PolynomialSemanticView.Monomial(List.of(0)),
            BigInteger.valueOf(4));
        return coefficients;
    }

    private static TreeMap<PolynomialSemanticView.Monomial, BigInteger>
            binaryQuarticCoefficients() {
        TreeMap<PolynomialSemanticView.Monomial, BigInteger> coefficients =
            new TreeMap<>();
        coefficients.put(
            new PolynomialSemanticView.Monomial(List.of(4, 0)),
            BigInteger.ONE);
        coefficients.put(
            new PolynomialSemanticView.Monomial(List.of(0, 4)),
            BigInteger.valueOf(4));
        return coefficients;
    }

    private static TreeMap<PolynomialSemanticView.Monomial, BigInteger>
            coefficientsWithLowerDegreeTerm() {
        TreeMap<PolynomialSemanticView.Monomial, BigInteger> coefficients =
            binaryQuarticCoefficients();
        coefficients.put(
            new PolynomialSemanticView.Monomial(List.of(1, 0)),
            BigInteger.valueOf(7));
        return coefficients;
    }

    private static boolean factorPair(
        PolynomialDecompositionSynthesisOperator.Candidate candidate,
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
