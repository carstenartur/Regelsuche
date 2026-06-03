package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class HypothesisOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void differenceOfSquaresPreparationGeneratesOrdinaryTransformations() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<Transformation> candidates = operator.generateCandidates("x^4 + 4");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(candidate ->
            DifferenceOfSquaresPreparationOperator.RULE_ID.equals(candidate.rule())));
        assertTrue(candidates.stream().allMatch(Transformation::equivalencePreservingByConstruction));
        assertTrue(candidates.stream().anyMatch(candidate ->
            SquareDifferenceAstPredicate.containsSquareDifference(candidate.transformedExpression())
                && candidate.transformedExpression().contains("2 * x")));
    }

    @Test
    void doesNotGeneratePairCandidateWhenAdditionalAddendsExist() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<Transformation> candidates = operator.generateCandidates("x^4 + 4 + y^2");

        assertTrue(candidates.isEmpty());
    }

    @Test
    void doesNotGenerateCandidatesForArbitraryNonSquareSums() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        assertTrue(operator.generateCandidates("x^4 + 5").isEmpty());
        assertTrue(operator.generateCandidates("x^4 + y").isEmpty());
    }

    @Test
    void squareDifferencePredicateTraversesFunctionArguments() {
        assertTrue(SquareDifferenceAstPredicate.containsSquareDifference("sin(x^2 - y^2)"));
    }

    @Test
    void respectsConfiguredCandidateBound() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator(0);

        assertTrue(operator.generateCandidates("x^4 + 4").isEmpty());
    }

    @Test
    void emitsDeterministicCandidateOrder() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<String> first = operator.generateCandidates("x^4 + 4").stream()
            .map(Transformation::transformedExpression)
            .toList();
        List<String> second = operator.generateCandidates("x^4 + 4").stream()
            .map(Transformation::transformedExpression)
            .toList();

        assertEquals(first, second);
    }

    @Test
    void extractsRootsFromProductsWithNumericAndSymbolicSquareFactors() {
        assertEquals("2 * x * y", squareRootOfProduct("4*x^2*y^2"));
        assertEquals("2 * (x + 1) * y", squareRootOfProduct("4*(x+1)^2*y^2"));
        assertEquals("2 * x * y", squareRootOfProduct("4*x*x*y^2"));
        assertNull(squareRootOfProduct("2*x^2*y^2"));
    }

    @Test
    void preparesGeneralSophieGermainBridgeWithSymbolicFourthPowers() {
        DifferenceOfSquaresPreparationOperator operator = new DifferenceOfSquaresPreparationOperator();

        List<String> candidates = operator.generateCandidates("x^4 + 4*y^4").stream()
            .map(Transformation::transformedExpression)
            .toList();

        assertTrue(candidates.stream().anyMatch(candidate ->
            candidate.equals("(x ^ 2 + 2 * y ^ 2) ^ 2 - (2 * x * y) ^ 2")), candidates.toString());
    }

    @Test
    void factorsRepeatedSubexpressionIntoSharedMultiplier() {
        RepeatedSubexpressionFactorizationHypothesisOperator operator =
            new RepeatedSubexpressionFactorizationHypothesisOperator();

        List<String> candidates = operator.generateCandidates("x * (y + 1) + z * (y + 1)").stream()
            .map(Transformation::transformedExpression)
            .toList();

        assertTrue(candidates.contains("(y + 1) * (x + z)"), candidates.toString());
    }

    @Test
    void rationalNormalizationCombinesSameDenominatorAndCancelsFactors() {
        RationalNormalizationHypothesisOperator operator = new RationalNormalizationHypothesisOperator();

        List<String> togetherCandidates = operator.generateCandidates("x / y + z / y").stream()
            .map(Transformation::transformedExpression)
            .toList();
        List<String> cancelCandidates = operator.generateCandidates("(x * z) / (y * z)").stream()
            .map(Transformation::transformedExpression)
            .toList();

        assertTrue(togetherCandidates.contains("(x + z) / y"), togetherCandidates.toString());
        assertTrue(cancelCandidates.contains("x / y"), cancelCandidates.toString());
    }

    @Test
    void repeatedSubexpressionAndRationalNormalizationAvoidNearMisses() {
        assertTrue(new RepeatedSubexpressionFactorizationHypothesisOperator()
            .generateCandidates("x * y + z * w").isEmpty());
        assertTrue(new RationalNormalizationHypothesisOperator()
            .generateCandidates("x / y + z / w").isEmpty());
    }

    @Test
    void factorCandidateOperatorEmitsSympyDerivedProvenanceAndContentCandidate() {
        FactorCandidateOperator operator = new FactorCandidateOperator();

        List<Transformation> candidates = operator.generateCandidates("2*x^2 + 4*x");

        assertTrue(candidates.stream().anyMatch(candidate -> candidate.transformedExpression().equals("2 * (x ^ 2 + 2 * x)")),
            candidates.toString());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.applicationKey().contains("source=sympy-derived")));
        assertTrue(candidates.stream().allMatch(candidate -> candidate.rule().equals(FactorCandidateOperator.RULE_ID)));
    }

    @Test
    void commonSubexpressionDiscoveryAliasesRepeatedFactorization() {
        CommonSubexpressionDiscoveryOperator operator = new CommonSubexpressionDiscoveryOperator();

        List<Transformation> candidates = operator.generateCandidates("x * (y + 1) + z * (y + 1)");

        assertTrue(candidates.stream().anyMatch(candidate -> candidate.transformedExpression().equals("(y + 1) * (x + z)")),
            candidates.toString());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.rule().equals(CommonSubexpressionDiscoveryOperator.RULE_ID)));
    }

    @Test
    void rationalDiscoveryToolkitCombinesTogetherCancelAndTelescopingCandidates() {
        RationalDiscoveryToolkitOperator operator = new RationalDiscoveryToolkitOperator();

        List<String> together = operator.generateCandidates("x / y + z / y").stream()
            .map(Transformation::transformedExpression)
            .toList();
        List<String> telescoping = operator.generateCandidates("1 / (n * (n + 1))").stream()
            .map(Transformation::transformedExpression)
            .toList();

        assertTrue(together.contains("(x + z) / y"), together.toString());
        assertTrue(telescoping.contains("1 / n - 1 / (n + 1)"), telescoping.toString());
    }


    @Test
    void trigOperatorsUnlockPythagoreanAndPowerReductionIdentities() {
        TrigPythagoreanIdentityOperator pythagorean = new TrigPythagoreanIdentityOperator();
        TrigPowerReductionOperator powerReduction = new TrigPowerReductionOperator();

        assertTrue(pythagorean.generateCandidates("sin(x)^2 + cos(x)^2").stream()
            .anyMatch(candidate -> candidate.transformedExpression().equals("1")));
        assertTrue(pythagorean.generateCandidates("tan(x)^2 + 1").stream()
            .anyMatch(candidate -> candidate.transformedExpression().equals("sec(x) ^ 2")));
        assertTrue(powerReduction.generateCandidates("1 - sin(x)^2").stream()
            .anyMatch(candidate -> candidate.transformedExpression().equals("cos(x) ^ 2")));
        assertTrue(powerReduction.generateCandidates("1 - cos(x)^2").stream()
            .anyMatch(candidate -> candidate.transformedExpression().equals("sin(x) ^ 2")));
    }

    @Test
    void assumptionAwareLogExpAndRootOperatorsEmitAssumptionsAndBlockUnknown() {
        LogProductAssumptionOperator logProduct = new LogProductAssumptionOperator();
        ExpLogInverseOperator expLogInverse = new ExpLogInverseOperator();
        PowerRootAssumptionRules rootRules = new PowerRootAssumptionRules();

        Transformation logCandidate = logProduct.generateCandidates("log(a*b)").getFirst();
        assertEquals("log(a) + log(b)", logCandidate.transformedExpression());
        assertEquals(List.of("a > 0", "b > 0"), logCandidate.assumptions());

        Transformation expCandidate = expLogInverse.generateCandidates("exp(log(x))").getFirst();
        assertEquals("x", expCandidate.transformedExpression());
        assertEquals(List.of("x > 0"), expCandidate.assumptions());

        Transformation rootCandidate = rootRules.generateCandidates("sqrt(x^2)").getFirst();
        assertEquals("x", rootCandidate.transformedExpression());
        assertEquals(List.of("x >= 0"), rootCandidate.assumptions());

        assertTrue(logProduct.generateCandidates("log(unknown_a*b)").isEmpty());
        assertTrue(expLogInverse.generateCandidates("exp(log(unknown_x))").isEmpty());
        assertTrue(rootRules.generateCandidates("sqrt(unknown_x^2)").isEmpty());
    }

    @Test
    void substitutionOperatorsIntroduceAndExpandPlaceholders() {
        SubstitutionIntroductionOperator introduction = new SubstitutionIntroductionOperator();
        SubstitutionExpansionOperator expansion = new SubstitutionExpansionOperator();

        List<Transformation> introduced = introduction.generateCandidates("(a+b)^2 + 6*(a+b) + 5");
        assertTrue(introduced.stream().anyMatch(candidate -> candidate.transformedExpression().equals("B ^ 2 + 6 * B + 5")));

        List<Transformation> expanded = expansion.generateCandidates("(B + 1) * (B + 5)");
        assertTrue(expanded.stream().anyMatch(candidate ->
            candidate.transformedExpression().equals("((a + b) + 1) * ((a + b) + 5)")));
    }

    private String squareRootOfProduct(String expression) {
        Expr product = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        Expr root = new DifferenceOfSquaresPreparationOperator().squareRootOfProduct(product);
        return root == null ? null : ExpressionFormatter.format(root);
    }
}
