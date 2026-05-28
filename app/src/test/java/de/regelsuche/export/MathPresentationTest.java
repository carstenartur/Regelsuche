package de.regelsuche.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(latex.contains("\\xrightarrow{\\text{collect like terms}}"), latex);
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

    @Test
    void detectComparatorFlipDetectsLessToGreater() {
        assertTrue(MathPresentation.detectComparatorFlip(
            "inequality_multiply_both_sides", "x < 3", "-x > -3"));
        assertTrue(MathPresentation.detectComparatorFlip(
            "inequality_divide_both_sides", "x \\le 3", "-x \\ge -3"));
    }

    @Test
    void detectComparatorFlipReturnsFalseWhenComparatorUnchanged() {
        assertFalse(MathPresentation.detectComparatorFlip(
            "inequality_multiply_both_sides", "x < 3", "2x < 6"));
    }

    @Test
    void detectComparatorFlipReturnsFalseForUnrelatedRule() {
        assertFalse(MathPresentation.detectComparatorFlip(
            "polynomial_distribute", "x < 3", "x > 3"));
    }

    @Test
    void alignedDerivationLatexWithDiffWrapsChangedTokensInHtmlClass() {
        // The token that changes between the two rows is `+1` -> `+2`.
        MathPresentation.DerivationStep step =
            new MathPresentation.DerivationStep("x+1", "x+2", "polynomial_collect_like_terms");
        String latex = math.alignedDerivationLatexWithDiff(java.util.List.of(step));
        assertTrue(latex.contains("\\htmlClass{diff-new}{"),
            "Stage 3 diff wrapper missing: " + latex);
        assertTrue(latex.contains("\\begin{aligned}"), latex);
    }

    @Test
    void alignedDerivationLatexWithDiffReturnsBlankForEmptyOrNull() {
        assertEquals("", math.alignedDerivationLatexWithDiff(null));
        assertEquals("", math.alignedDerivationLatexWithDiff(java.util.List.of()));
    }

    @Test
    void derivationStepBackCompatConstructorPopulatesDiffAndFlip() {
        MathPresentation.DerivationStep step = new MathPresentation.DerivationStep(
            "x<3", "-x>-3", "inequality_multiply_both_sides");
        assertTrue(step.comparatorFlipped(),
            "comparator flip must be inferred server-side");
        assertFalse(step.changedToSpans().isEmpty(),
            "diff spans must be computed by the back-compat ctor");
    }
}
