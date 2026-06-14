package de.regelsuche.ide;

import de.regelsuche.ast.BinaryExpr;
import de.regelsuche.ast.Expr;
import de.regelsuche.ast.FunctionExpr;
import de.regelsuche.moves.MoveCandidateTransformationEngine;
import de.regelsuche.moves.MoveParameter;
import de.regelsuche.moves.MoveRealizer;
import de.regelsuche.moves.enumerate.Depth1MoveEnumerator.CandidateMove;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies tree-local candidate moves to a full root expression.
 *
 * <p>The applier is intentionally framework-free: it depends only on the AST,
 * move enumeration/realization infrastructure and parser/formatter. Internally
 * it navigates and replaces subtrees by {@link TreePosition#path()}, while
 * strings are used only at the parser/formatter boundary and in the returned
 * DTO-like result.</p>
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

    public LocalRewriteResult apply(String rootExpression, TreePosition position, CandidateMove candidate) {
        return apply(rootExpression, position, candidate == null ? List.of() : List.of(candidate));
    }

    public LocalRewriteResult apply(String rootExpression, TreePosition position, List<CandidateMove> candidates) {
        List<CandidateMove> source = candidates == null ? List.of() : List.copyOf(candidates);
        if (rootExpression == null || rootExpression.isBlank()) {
            return failure(rootExpression, position, source, "", "root expression is blank");
        }
        if (position == null) {
            return failure(rootExpression, null, source, "", "position is missing");
        }
        if (source.isEmpty()) {
            return failure(rootExpression, position, source, "", "candidate move is missing");
        }

        Expr root;
        try {
            root = parser.parseTerm(rootExpression);
        } catch (RuntimeException exception) {
            return failure(rootExpression, position, source, "", "root expression is not parseable");
        }

        Expr subtree = subtreeAt(root, position.path());
        if (subtree == null) {
            return failure(rootExpression, position, source, "", "position is not present in expression");
        }
        String subtreeBefore = ExpressionFormatter.format(subtree);
        if (!subtreeBefore.equals(position.text())) {
            return failure(rootExpression, position, source, subtreeBefore, "position is stale");
        }

        MoveCandidateTransformationEngine.MoveBackedTransformation realized = selectRealizedMove(subtreeBefore, source);
        if (realized == null) {
            return failure(rootExpression, position, source, subtreeBefore, "candidate could not be realized");
        }

        String subtreeAfter = realized.transformation().transformedExpression();
        Expr replacement;
        try {
            replacement = parser.parseTerm(subtreeAfter);
        } catch (RuntimeException exception) {
            return failure(rootExpression, position, source, subtreeBefore, "realized subtree is not parseable");
        }

        Expr rewrittenRoot = replaceAt(root, position.path(), replacement);
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

    private MoveCandidateTransformationEngine.MoveBackedTransformation selectRealizedMove(
            String subtreeBefore,
            List<CandidateMove> candidates) {
        List<MoveCandidateTransformationEngine.MoveBackedTransformation> realized =
                realizer.realize(subtreeBefore, candidates);
        CandidateMove first = candidates.getFirst();
        List<String> values = candidates.stream()
                .filter(candidate -> candidate.parameter() != null)
                .map(candidate -> candidate.parameter().value())
                .toList();
        return realized.stream()
                .filter(candidate -> candidate.move().kind() == first.kind())
                .filter(candidate -> values.isEmpty()
                        || values.stream().allMatch(value -> candidate.move().parameters().stream()
                                .anyMatch(parameter -> value.equals(parameter.value()))))
                .findFirst()
                .orElse(null);
    }

    private static Expr subtreeAt(Expr expr, List<Integer> path) {
        Expr current = expr;
        for (int index : path) {
            if (current instanceof BinaryExpr binary) {
                current = switch (index) {
                    case 0 -> binary.left();
                    case 1 -> binary.right();
                    default -> null;
                };
            } else if (current instanceof FunctionExpr function) {
                current = index >= 0 && index < function.arguments().size()
                        ? function.arguments().get(index)
                        : null;
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static Expr replaceAt(Expr expr, List<Integer> path, Expr replacement) {
        if (path.isEmpty()) {
            return replacement;
        }
        int index = path.getFirst();
        List<Integer> tail = path.subList(1, path.size());
        if (expr instanceof BinaryExpr binary) {
            return switch (index) {
                case 0 -> new BinaryExpr(replaceAt(binary.left(), tail, replacement), binary.operator(), binary.right());
                case 1 -> new BinaryExpr(binary.left(), binary.operator(), replaceAt(binary.right(), tail, replacement));
                default -> throw new IllegalArgumentException("invalid binary path index: " + index);
            };
        }
        if (expr instanceof FunctionExpr function) {
            List<Expr> arguments = new ArrayList<>(function.arguments());
            arguments.set(index, replaceAt(arguments.get(index), tail, replacement));
            return new FunctionExpr(function.name(), arguments);
        }
        throw new IllegalArgumentException("path descends into a leaf expression");
    }

    private static List<MoveParameter> bindings(List<CandidateMove> candidates) {
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
            String reason) {
        CandidateMove first = candidates == null || candidates.isEmpty() ? null : candidates.getFirst();
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

    public record LocalRewriteResult(
            String originalExpression,
            TreePosition position,
            String subtreeBefore,
            String subtreeAfter,
            String expressionAfter,
            String kind,
            List<MoveParameter> bindings,
            boolean success,
            String failureReason) {

        public LocalRewriteResult {
            originalExpression = originalExpression == null ? "" : originalExpression;
            subtreeBefore = subtreeBefore == null ? "" : subtreeBefore;
            kind = kind == null ? "" : kind;
            bindings = bindings == null ? List.of() : List.copyOf(bindings);
            failureReason = failureReason == null ? "" : failureReason;
        }
    }
}
