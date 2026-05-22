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
}
