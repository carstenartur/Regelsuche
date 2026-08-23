package de.regelsuche.polynomial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.Expr;
import de.regelsuche.input.InputRequest;
import de.regelsuche.input.InputType;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialSemanticViewTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void infersCompositePowerBasesAsSemanticGenerators() {
        PolynomialSemanticView.Result result = new PolynomialSemanticView()
            .analyze(parse("(x + 1)^4 + 4*y^4"));

        assertTrue(result.supported(), result.detailCode());
        assertEquals(List.of("(x + 1)", "y"), result.view().generators().stream()
            .map(PolynomialSemanticView.Generator::key)
            .toList());
        assertTrue(result.view().polynomial().homogeneous());
        assertEquals(4, result.view().polynomial().totalDegree());
        assertTrue(result.view().semanticHash().matches("sha256:[0-9a-f]{64}"));
        assertEquals(result.view().semanticHash(), new PolynomialSemanticView()
            .analyze(parse("(x + 1)^4 + 4*y^4"))
            .view().semanticHash());
    }

    @Test
    void treatsFunctionApplicationsAsOrdinarySemanticGenerators() {
        PolynomialSemanticView.Result result = new PolynomialSemanticView()
            .analyze(parse("sin(t)^4 + 4*z^4"));

        assertTrue(result.supported(), result.detailCode());
        assertEquals(List.of("sin(t)", "z"), result.view().generators().stream()
            .map(PolynomialSemanticView.Generator::key)
            .toList());
    }

    @Test
    void distinguishesUnsupportedInputFromBudgetExhaustion() {
        PolynomialSemanticView.Result division = new PolynomialSemanticView()
            .analyze(parse("x / y + 1"));
        PolynomialSemanticView.Result exhausted = new PolynomialSemanticView(
            new PolynomialSemanticView.Budget(1, 16, 32, 4, 8, 8, 32))
            .analyze(parse("x + y"));

        assertEquals(PolynomialSemanticView.Status.UNSUPPORTED, division.status());
        assertEquals("DIVISION_NOT_IN_EXACT_POLYNOMIAL_VIEW", division.detailCode());
        assertEquals(
            PolynomialSemanticView.Status.BUDGET_EXCEEDED,
            exhausted.status());
        assertEquals("MAX_VISITED_NODES_EXCEEDED", exhausted.detailCode());
        assertFalse(division.supported());
        assertFalse(exhausted.supported());
    }

    private Expr parse(String expression) {
        return parser.parse(new InputRequest(InputType.TERM, expression))
            .terms().getFirst();
    }
}
