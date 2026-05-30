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

    private String squareRootOfProduct(String expression) {
        Expr product = parser.parse(new InputRequest(InputType.TERM, expression)).terms().getFirst();
        Expr root = new DifferenceOfSquaresPreparationOperator().squareRootOfProduct(product);
        return root == null ? null : ExpressionFormatter.format(root);
    }
}
