package de.regelsuche.moves.apply;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.MoveCandidateTransformationEngine;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveRealizer;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.List;

/**
 * Applies tree-local candidate moves to a full root expression.
 *
 * <p>The applier is intentionally framework-free: it depends only on the AST,
 * move enumeration/realization infrastructure and parser/formatter. Navigation
 * and replacement are delegated to {@link TreeExpressionEditor}, the shared
 * structural authority for stable child-index paths. Strings are used only at
 * the parser/formatter boundary and in the returned {@link LocalRewriteResult}.</p>
 *
 * <p>Two entry-point families are provided:
 * <ul>
 *   <li><b>String-based</b> ({@code apply(String, ...)}) — parses the root
 *       expression first, then delegates to the {@code Expr}-based path.</li>
 *   <li><b>Expr-based</b> ({@code apply(Expr, ...)}) — accepts an already-parsed
 *       AST root and skips the parse step. Prefer this overload when the caller
 *       already holds the parsed tree to avoid redundant round-trips through the
 *       parser.</li>
 * </ul>
 * Both families return the same {@link LocalRewriteResult} record.</p>
 */
public final class LocalRewriteApplier {

    private final MoveRealizer realizer;
    private final ExpressionParser parser;

    public LocalRewriteApplier() {
        this(new MoveRealizer(), new ExpressionParser());
    }

    public LocalRewriteApplier(MoveRealizer realizer, ExpressionParser parser) {
        this.realizer = realizer == null ? new MoveRealizer() : realizer;
        this.parser = parser == null ? new ExpressionParser() : parser;
    }

    // ── String-based entry points ─────────────────────────────────────────────

    /**
     * Applies a single candidate move to the subtree at {@code position} within
     * {@code rootExpression}.
     */
    public LocalRewriteResult apply(
        String rootExpression,
        TreePosition position,
        CandidateMove candidate
    ) {
        return apply(
            rootExpression,
            position,
            candidate == null ? List.of() : List.of(candidate));
    }

    /** Applies one logical candidate group at a selected subtree position. */
    public LocalRewriteResult apply(
        String rootExpression,
        TreePosition position,
        List<CandidateMove> candidates
    ) {
        List<CandidateMove> source = candidates == null
            ? List.of()
            : List.copyOf(candidates);
        if (rootExpression == null || rootExpression.isBlank()) {
            return failure(
                rootExpression,
                position,
                source,
                "",
                "root expression is blank");
        }
        if (position == null) {
            return failure(
                rootExpression,
                null,
                source,
                "",
                "position is missing");
        }
        if (source.isEmpty()) {
            return failure(
                rootExpression,
                position,
                source,
                "",
                "candidate move is missing");
        }

        Expr root;
        try {
            root = parser.parseTerm(rootExpression);
        } catch (RuntimeException exception) {
            return failure(
                rootExpression,
                position,
                source,
                "",
                "root expression is not parseable");
        }
        return applyToExpr(root, rootExpression, position, source);
    }

    // ── Expr-based entry points ────────────────────────────────────────────────

    /** Applies a single candidate move to an already parsed root AST. */
    public LocalRewriteResult apply(
        Expr root,
        TreePosition position,
        CandidateMove candidate
    ) {
        return apply(
            root,
            position,
            candidate == null ? List.of() : List.of(candidate));
    }

    /** Applies one logical candidate group to an already parsed root AST. */
    public LocalRewriteResult apply(
        Expr root,
        TreePosition position,
        List<CandidateMove> candidates
    ) {
        List<CandidateMove> source = candidates == null
            ? List.of()
            : List.copyOf(candidates);
        if (root == null) {
            return failure(
                null,
                position,
                source,
                "",
                "root expression is missing");
        }
        if (position == null) {
            return failure(
                ExpressionFormatter.format(root),
                null,
                source,
                "",
                "position is missing");
        }
        if (source.isEmpty()) {
            return failure(
                ExpressionFormatter.format(root),
                position,
                source,
                "",
                "candidate move is missing");
        }

        String rootExpression = ExpressionFormatter.format(root);
        return applyToExpr(root, rootExpression, position, source);
    }

    // ── Core implementation ───────────────────────────────────────────────────

    private LocalRewriteResult applyToExpr(
        Expr root,
        String rootExpression,
        TreePosition position,
        List<CandidateMove> source
    ) {
        Expr subtree = TreeExpressionEditor.subtreeAt(
            root,
            position.path()).orElse(null);
        if (subtree == null) {
            return failure(
                rootExpression,
                position,
                source,
                "",
                "position is not present in expression");
        }
        String subtreeBefore = ExpressionFormatter.format(subtree);
        if (!subtreeBefore.equals(position.text())) {
            return failure(
                rootExpression,
                position,
                source,
                subtreeBefore,
                "position is stale");
        }

        MoveCandidateTransformationEngine.MoveBackedTransformation realized =
            selectRealizedMove(subtreeBefore, source);
        if (realized == null) {
            return failure(
                rootExpression,
                position,
                source,
                subtreeBefore,
                "candidate could not be realized");
        }

        String subtreeAfter =
            realized.transformation().transformedExpression();
        Expr replacement;
        try {
            replacement = parser.parseTerm(subtreeAfter);
        } catch (RuntimeException exception) {
            return failure(
                rootExpression,
                position,
                source,
                subtreeBefore,
                "realized subtree is not parseable");
        }

        TreeExpressionEditor.ReplacementResult replacementResult =
            TreeExpressionEditor.replaceAt(
                root,
                position.path(),
                replacement);
        if (!replacementResult.success()) {
            return failure(
                rootExpression,
                position,
                source,
                subtreeBefore,
                "position is not present in expression");
        }
        Expr rewrittenRoot = replacementResult.rewrittenRoot().orElseThrow();
        String expressionAfter = ExpressionFormatter.format(rewrittenRoot);
        CandidateMove first = source.getFirst();
        return new LocalRewriteResult(
            rootExpression,
            position,
            subtreeBefore,
            subtreeAfter,
            expressionAfter,
            first.kind().name(),
            bindings(source),
            true,
            "");
    }

    private MoveCandidateTransformationEngine.MoveBackedTransformation
            selectRealizedMove(
        String subtreeBefore,
        List<CandidateMove> candidates
    ) {
        List<MoveCandidateTransformationEngine.MoveBackedTransformation>
            realized = realizer.realize(subtreeBefore, candidates);
        CandidateMove first = candidates.getFirst();
        List<String> values = candidates.stream()
            .filter(candidate -> candidate.parameter() != null)
            .map(candidate -> candidate.parameter().value())
            .toList();
        return realized.stream()
            .filter(candidate -> candidate.move().kind() == first.kind())
            .filter(candidate -> values.isEmpty()
                || values.stream().allMatch(value ->
                    candidate.move().parameters().stream()
                        .anyMatch(parameter ->
                            value.equals(parameter.value()))))
            .findFirst()
            .orElse(null);
    }

    private static List<MoveParameter> bindings(
        List<CandidateMove> candidates
    ) {
        return candidates.stream()
            .map(CandidateMove::parameter)
            .filter(parameter -> parameter != null)
            .toList();
    }

    private static LocalRewriteResult failure(
        String rootExpression,
        TreePosition position,
        List<CandidateMove> candidates,
        String subtreeBefore,
        String reason
    ) {
        CandidateMove first = candidates == null || candidates.isEmpty()
            ? null
            : candidates.getFirst();
        return new LocalRewriteResult(
            rootExpression == null ? "" : rootExpression,
            position,
            subtreeBefore == null ? "" : subtreeBefore,
            null,
            null,
            first == null ? "" : first.kind().name(),
            candidates == null ? List.of() : bindings(candidates),
            false,
            reason == null ? "" : reason);
    }

    /** Result of a local rewrite attempt. */
    public record LocalRewriteResult(
        String originalExpression,
        TreePosition position,
        String subtreeBefore,
        String subtreeAfter,
        String expressionAfter,
        String kind,
        List<MoveParameter> bindings,
        boolean success,
        String failureReason
    ) {
        public LocalRewriteResult {
            originalExpression = originalExpression == null
                ? ""
                : originalExpression;
            subtreeBefore = subtreeBefore == null ? "" : subtreeBefore;
            kind = kind == null ? "" : kind;
            bindings = bindings == null
                ? List.of()
                : List.copyOf(bindings);
            failureReason = failureReason == null ? "" : failureReason;
        }
    }
}
