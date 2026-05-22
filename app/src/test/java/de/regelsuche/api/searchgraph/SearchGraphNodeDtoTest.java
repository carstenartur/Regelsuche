package de.regelsuche.api.searchgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.regelsuche.export.MathPresentation;
import de.regelsuche.mining.CandidateProofStatus;
import org.junit.jupiter.api.Test;

/**
 * Stage 4 pin: every {@link SearchGraphNodeDto} carries a non-blank
 * {@code expressionLatex} field routed through
 * {@link MathPresentation#latex(String)} so the Cytoscape KaTeX overlay
 * layer can render the expression without having to re-parse the AST.
 *
 * <p>The back-compat constructors (used by hand-written tests, codec
 * readers and existing call sites) must auto-populate the field so
 * Stage-4-aware UI code does not need to special-case the empty
 * string.</p>
 */
class SearchGraphNodeDtoTest {

    @Test
    void backCompatTenArgCtorPopulatesExpressionLatexFromLatexField() {
        SearchGraphNodeDto node = new SearchGraphNodeDto(
            "n1", "x*y", "x \\cdot y", 0, 0, 1, false, false,
            CandidateProofStatus.OBSERVED, ""
        );
        assertNotNull(node.expressionLatex());
        assertFalse(node.expressionLatex().isBlank(),
            "expressionLatex must default to non-blank value");
        assertEquals("x \\cdot y", node.expressionLatex(),
            "expressionLatex defaults to existing latex field when available");
    }

    @Test
    void compactCtorBackfillsExpressionLatexWhenLatexFieldIsBlank() {
        SearchGraphNodeDto node = new SearchGraphNodeDto(
            "n2", "x*y", "", 0, 0, 1, false, false,
            CandidateProofStatus.OBSERVED, ""
        );
        assertNotNull(node.expressionLatex());
        assertFalse(node.expressionLatex().isBlank(),
            "expressionLatex must fall back to MathPresentation.latex(expression)");
        assertEquals(MathPresentation.DEFAULT.latex("x*y"), node.expressionLatex());
    }

    @Test
    void explicitExpressionLatexIsPreserved() {
        SearchGraphNodeDto node = new SearchGraphNodeDto(
            "n3", "x", "x", 0, 0, 1, false, false,
            CandidateProofStatus.OBSERVED, "",
            SearchExpression.classify("x"), "\\boxed{x}"
        );
        assertEquals("\\boxed{x}", node.expressionLatex());
    }
}
