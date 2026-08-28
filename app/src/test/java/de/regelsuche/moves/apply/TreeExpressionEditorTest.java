package de.regelsuche.moves.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.ast.VariableExpr;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreeExpressionEditorTest {
    private final ExpressionParser parser = new ExpressionParser();

    @Test
    void replacesTheRootWithoutCopyingAnAncestor() {
        Expr root = parser.parseTerm("x + 1");
        Expr replacement = new VariableExpr("y");

        var result = TreeExpressionEditor.replaceAt(
            root,
            List.of(),
            replacement);

        assertTrue(result.success());
        assertSame(root, result.selectedSubtree().orElseThrow());
        assertSame(replacement, result.rewrittenRoot().orElseThrow());
        assertEquals(0, result.copiedAncestors());
    }

    @Test
    void replacesOneNestedOccurrenceAndPreservesUntouchedReferences() {
        FunctionExpr root = (FunctionExpr) parser.parseTerm("f(x + y, z)");
        BinaryExpr originalSum = (BinaryExpr) root.arguments().getFirst();
        Expr untouchedLeft = originalSum.left();
        Expr untouchedSecondArgument = root.arguments().get(1);
        Expr replacement = new VariableExpr("t");

        var result = TreeExpressionEditor.replaceAt(
            root,
            List.of(0, 1),
            replacement);

        assertTrue(result.success());
        assertSame(originalSum.right(), result.selectedSubtree().orElseThrow());
        assertEquals(2, result.copiedAncestors());
        FunctionExpr rewritten = (FunctionExpr) result.rewrittenRoot()
            .orElseThrow();
        BinaryExpr rewrittenSum = (BinaryExpr) rewritten.arguments().getFirst();
        assertSame(untouchedLeft, rewrittenSum.left());
        assertSame(replacement, rewrittenSum.right());
        assertSame(untouchedSecondArgument, rewritten.arguments().get(1));
        assertEquals("f(x + t, z)", ExpressionFormatter.format(rewritten));
    }

    @Test
    void distinguishesInvalidAndMissingPaths() {
        Expr root = parser.parseTerm("x + 1");
        Expr replacement = new VariableExpr("y");

        var invalid = TreeExpressionEditor.replaceAt(
            root,
            List.of(-1),
            replacement);
        var missing = TreeExpressionEditor.replaceAt(
            root,
            List.of(2),
            replacement);

        assertFalse(invalid.success());
        assertEquals(
            TreeExpressionEditor.Status.INVALID_PATH,
            invalid.status());
        assertFalse(missing.success());
        assertEquals(
            TreeExpressionEditor.Status.POSITION_NOT_PRESENT,
            missing.status());
        assertTrue(TreeExpressionEditor.subtreeAt(root, List.of(2)).isEmpty());
    }

    @Test
    void handlesDeepPathsWithoutRecursiveReplacement() {
        int depth = 8_000;
        Expr root = new VariableExpr("x");
        List<Integer> path = new ArrayList<>(depth);
        for (int index = 0; index < depth; index++) {
            root = new FunctionExpr("f", List.of(root));
            path.add(0);
        }
        Expr replacement = new VariableExpr("y");

        var result = TreeExpressionEditor.replaceAt(
            root,
            path,
            replacement);

        assertTrue(result.success());
        assertEquals(depth, result.copiedAncestors());
        assertSame(
            replacement,
            TreeExpressionEditor.subtreeAt(
                result.rewrittenRoot().orElseThrow(),
                path).orElseThrow());
    }
}
