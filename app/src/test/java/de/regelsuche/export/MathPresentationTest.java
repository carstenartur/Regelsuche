package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MathPresentationTest {

    private final MathPresentation math = MathPresentation.DEFAULT;

    @Test
    void latexHandlesNullAndBlank() {
        assertEquals("", math.latex(null));
        assertEquals("", math.latex(""));
        assertEquals("", math.latex("   "));
    }

    @Test
    void latexRendersSimpleExpressionViaAstRenderer() {
        String latex = math.latex("x*y");
        assertNotNull(latex);
        assertTrue(latex.contains("\\cdot") || latex.contains("xy"),
            "expected AstLatexRenderer multiplication, got: " + latex);
    }

    @Test
    void inlineMathWrapsInDollarSigns() {
        assertEquals("$x+1$", math.inlineMath("x+1"));
        assertEquals("", math.inlineMath(null));
    }

    @Test
    void ruleLatexReturnsCuratedLabelForKnownRule() {
        String label = math.ruleLatex("inequality_divide_both_sides");
        assertNotNull(label);
        assertTrue(label.contains("\\div") || label.contains("\\text"),
            "expected curated label, got: " + label);
    }

    @Test
    void ruleLatexFallsBackToTextWrapper() {
        String label = math.ruleLatex("some_unknown_rule_xyz");
        assertTrue(label.startsWith("\\text{"), "got: " + label);
    }

    @Test
    void alignedDerivationLatexEmitsBeginAlignedWithArrows() {
        String latex = math.alignedDerivationLatex(java.util.List.of(
            new MathPresentation.DerivationStep("(x+3)^2", "x(x+3)+3(x+3)", "polynomial_distribute"),
            new MathPresentation.DerivationStep("x(x+3)+3(x+3)", "x^2+6x+9", "polynomial_collect_like_terms")
        ));
        assertTrue(latex.startsWith("\\begin{aligned}"), latex);
        assertTrue(latex.endsWith("\\end{aligned}"), latex);
        assertTrue(latex.contains("(x+3)^2"));
        assertTrue(latex.contains("\\xrightarrow{a(b+c)\\to ab+ac}"), latex);
        assertTrue(latex.contains("\\xrightarrow{ax+bx\\to(a+b)x}"), latex);
        assertTrue(latex.contains(" \\\\\n&"), "rows must be separated by `\\\\\\n&`: " + latex);
    }

    @Test
    void alignedDerivationLatexReturnsBlankForEmptyOrNull() {
        assertEquals("", math.alignedDerivationLatex(null));
        assertEquals("", math.alignedDerivationLatex(java.util.List.of()));
    }

    @Test
    void alignedDerivationLatexFallsBackToPlainArrowWhenRuleIsBlank() {
        String latex = math.alignedDerivationLatex(java.util.List.of(
            new MathPresentation.DerivationStep("a", "b", "")
        ));
        assertTrue(latex.contains("&\\rightarrow b"), latex);
    }
}
