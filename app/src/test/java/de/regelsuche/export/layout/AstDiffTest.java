package de.regelsuche.export.layout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.export.AstLatexRenderer;
import de.regelsuche.parse.ExpressionParser;
import org.junit.jupiter.api.Test;

class AstDiffTest {

    private final ExpressionParser parser = new ExpressionParser();
    private final AstLatexRenderer renderer = new AstLatexRenderer();

    @Test
    void distributiveRewriteKeepsSharedLeavesUnmarked() {
        AstDiff.Result diff = AstDiff.diff(
            parser.parseTerm("x*(y+1)"),
            parser.parseTerm("x*y+x"),
            renderer
        );

        assertTrue(diff.toNodes().stream()
                .anyMatch(node -> node.text().contains("x")
                    && !node.attributes().containsKey("class")),
            "shared x leaf should remain unmarked in the target layout");
        assertTrue(diff.toNodes().stream()
                .anyMatch(node -> "diff-new".equals(node.attributes().get("class"))),
            "changed operator structure should still be highlighted");
    }

    @Test
    void factorizationRewriteKeepsCommonFactorUnmarked() {
        AstDiff.Result diff = AstDiff.diff(
            parser.parseTerm("a*x+a*y"),
            parser.parseTerm("a*(x+y)"),
            renderer
        );

        assertTrue(diff.toNodes().stream()
                .anyMatch(node -> node.text().contains("a")
                    && !node.attributes().containsKey("class")),
            "common factor should stay unmarked in the factorized target");
        assertFalse(diff.toNodes().isEmpty(), "AST diff must emit target nodes");
    }
}
