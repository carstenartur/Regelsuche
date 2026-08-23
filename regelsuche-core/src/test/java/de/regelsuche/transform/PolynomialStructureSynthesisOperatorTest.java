package de.regelsuche.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.PolynomialSemanticView;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialStructureSynthesisOperatorTest {
    private final ExpressionParser parser = new ExpressionParser();
    private final PolynomialSemanticView semanticView =
        new PolynomialSemanticView();

    @Test
    void factorsHiddenQuarticStructureWithOneGenericTheoryOperator() {
        List<Transformation> candidates =
            new PolynomialStructureSynthesisOperator()
                .generateCandidates("x^4 + 4*y^4");

        assertFalse(candidates.isEmpty());
        assertTrue(candidates.stream().allMatch(candidate ->
            candidate.rule().equals(PolynomialStructureSynthesisOperator.RULE_ID)));
        assertTrue(candidates.stream().allMatch(candidate ->
            candidate.packId().equals(PolynomialStructureSynthesisOperator.PACK_ID)));
        assertTrue(candidates.stream().allMatch(
            Transformation::equivalencePreservingByConstruction));
        assertTrue(candidates.stream().allMatch(candidate ->
            candidate.applicationKey().contains("algorithm=regelsuche.exact-polynomial-decomposition-synthesis/v1")
                && candidate.applicationKey().contains("|certificate=sha256:")));
        assertTrue(candidates.stream().noneMatch(candidate ->
            candidate.applicationKey().toLowerCase(java.util.Locale.ROOT)
                .contains("sophie")));
        assertEquivalent("x^4 + 4*y^4", candidates.getFirst().transformedExpression());
    }

    @Test
    void handlesArbitraryAstGeneratorsWithoutExplicitSubstitution() {
        for (String expression : List.of(
                "(x + 1)^4 + 4*y^4",
                "sin(t)^4 + 4*z^4",
                "x^4 + 5*x^2*y^2 + 4*y^4")) {
            List<Transformation> candidates =
                new PolynomialStructureSynthesisOperator()
                    .generateCandidates(expression);
            assertFalse(candidates.isEmpty(), expression);
            assertEquivalent(expression, candidates.getFirst().transformedExpression());
        }
    }

    @Test
    void rejectsNearMissesAndRespectsCandidateBudget() {
        PolynomialStructureSynthesisOperator operator =
            new PolynomialStructureSynthesisOperator();

        assertTrue(operator.generateCandidates("x^4 + 3*y^4").isEmpty());
        assertTrue(operator.generateCandidates("x^4 + 4*y^3").isEmpty());
        assertTrue(new PolynomialStructureSynthesisOperator(0)
            .generateCandidates("x^4 + 4*y^4").isEmpty());
        assertEquals(1, new PolynomialStructureSynthesisOperator(1)
            .generateCandidates("x^4 + 4*y^4").size());
    }

    private void assertEquivalent(String source, String target) {
        PolynomialSemanticView.Result left = semanticView.analyze(parse(source));
        PolynomialSemanticView.Result right = semanticView.analyze(parse(target));
        assertTrue(left.supported(), left.detailCode());
        assertTrue(right.supported(), right.detailCode());
        assertEquals(
            left.view().polynomial().canonical(),
            right.view().polynomial().canonical());
    }

    private Expr parse(String expression) {
        return parser.parse(new InputRequest(InputType.TERM, expression))
            .terms().getFirst();
    }
}
