package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AstLatexRendererTest {

    private final AstLatexRenderer renderer = new AstLatexRenderer();

    @Test
    void exactNumericLeavesRenderWithoutRounding() {
        assertEquals("9007199254740993", renderer.renderExpression("9007199254740993"));
        assertEquals("\\frac{1}{3}", renderer.render(de.regelsuche.ast.NumberExpr.exact("1/3"), 0));
        var negativeBase = new de.regelsuche.ast.BinaryExpr(
            de.regelsuche.ast.NumberExpr.exact("-1/3"), de.regelsuche.ast.BinaryOperator.POW,
            new de.regelsuche.ast.NumberExpr(2));
        assertTrue(renderer.render(negativeBase, 0).contains("\\left("));
        assertTrue(new AstMathMlRenderer().renderExpression("9007199254740993")
            .contains("<mn>9007199254740993</mn>"));
    }

    @Test
    void rendersDivisionAsFrac() {
        String result = renderer.renderExpression("(a + b) / 2");
        assertTrue(result.contains("\\frac{"), result);
        assertFalse(result.contains("/"), result);
    }

    @Test
    void rendersPowerAsCaret() {
        String result = renderer.renderExpression("x^2 + 1");
        assertTrue(result.contains("^{"), result);
    }

    @Test
    void rendersTrigonometricFunctions() {
        String result = renderer.renderExpression("sin(x) + cos(x)");
        assertTrue(result.contains("\\sin"), result);
        assertTrue(result.contains("\\cos"), result);
    }

    @Test
    void rendersSqrt() {
        assertTrue(renderer.renderExpression("sqrt(x + 1)").contains("\\sqrt{"));
    }

    @Test
    void respectsPrecedenceParenthesisation() {
        String result = renderer.renderExpression("(a + b) * c");
        assertTrue(result.contains("\\left("), result);
    }

    @Test
    void rendersEquation() {
        String result = renderer.renderExpression("2*x + 3 = 7");
        assertTrue(result.contains(" = "), result);
        assertTrue(result.contains("\\cdot"), result);
    }

    @Test
    void rendersEquationSystem() {
        String result = renderer.renderExpression("x + y = 1;\nx - y = 0");
        assertTrue(result.contains("\\begin{cases}"), result);
        assertTrue(result.contains("\\end{cases}"), result);
    }

    @Test
    void fallsBackOnUnparseableInput() {
        String result = renderer.renderExpression(":::");
        assertEquals(":::", result);
    }

    @Test
    void mathMlRendererProducesMathMlElements() {
        AstMathMlRenderer ml = new AstMathMlRenderer();
        String result = ml.renderExpression("(a + b) / 2");
        assertTrue(result.contains("<math"), result);
        assertTrue(result.contains("<mfrac>"), result);
    }
}
