package de.regelsuche.export.layout;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.BinaryOperator;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.export.AstLatexRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Heuristic AST diff for layout rendering.
 *
 * <p>Instead of diffing the rendered LaTeX string, this compares raw
 * expression trees and keeps any subtree that still appears somewhere in the
 * opposite tree unmarked. Changed structure is surfaced as CSS classes on the
 * emitted {@link MathLayoutNode} fragments.</p>
 */
public final class AstDiff {

    private AstDiff() {
    }

    public record Result(List<MathLayoutNode> fromNodes, List<MathLayoutNode> toNodes) {
        public Result {
            fromNodes = fromNodes == null ? List.of() : List.copyOf(fromNodes);
            toNodes = toNodes == null ? List.of() : List.copyOf(toNodes);
        }
    }

    public static Result diff(Expr from, Expr to, AstLatexRenderer renderer) {
        Objects.requireNonNull(renderer, "renderer");
        return new Result(
            nodesFor(from, to, renderer, 0, "diff-old"),
            nodesFor(to, from, renderer, 0, "diff-new")
        );
    }

    private static List<MathLayoutNode> nodesFor(
        Expr expr,
        Expr oppositeRoot,
        AstLatexRenderer renderer,
        int parentPrecedence,
        String cssClass
    ) {
        if (expr == null) {
            return List.of();
        }
        if (subtreeContains(oppositeRoot, expr)) {
            return List.of(MathLayoutNode.fragment(renderer.render(expr, parentPrecedence)));
        }
        if (expr instanceof BinaryExpr binary
            && binary.operator() != BinaryOperator.DIV
            && binary.operator() != BinaryOperator.POW
            && binary.operator().precedence() >= parentPrecedence) {
            List<MathLayoutNode> out = new ArrayList<>();
            int leftParent = binary.operator().precedence();
            int rightParent = binary.operator() == BinaryOperator.SUB
                ? binary.operator().precedence() + 1
                : binary.operator().precedence();
            out.addAll(nodesFor(binary.left(), oppositeRoot, renderer, leftParent, cssClass));
            out.add(MathLayoutNode.fragment(operatorLatex(binary.operator()), cssClass));
            out.addAll(nodesFor(binary.right(), oppositeRoot, renderer, rightParent, cssClass));
            return out;
        }
        return List.of(MathLayoutNode.fragment(renderer.render(expr, parentPrecedence), cssClass));
    }

    private static boolean subtreeContains(Expr root, Expr target) {
        if (root == null || target == null) {
            return false;
        }
        if (root.equals(target)) {
            return true;
        }
        if (root instanceof BinaryExpr binary) {
            return subtreeContains(binary.left(), target) || subtreeContains(binary.right(), target);
        }
        if (root instanceof FunctionExpr fn) {
            for (Expr argument : fn.arguments()) {
                if (subtreeContains(argument, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String operatorLatex(BinaryOperator operator) {
        return switch (operator) {
            case ADD -> " + ";
            case SUB -> " - ";
            case MUL -> " \\cdot ";
            case DIV -> " / ";
            case POW -> "^";
        };
    }
}
