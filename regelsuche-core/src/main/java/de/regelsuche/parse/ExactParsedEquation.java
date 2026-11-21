package de.regelsuche.parse;

import de.regelsuche.ast.Equation;
import de.regelsuche.ast.Expr;
import de.regelsuche.parse.ExactParsedTerm.SourceRange;
import java.util.Optional;

/** A parsed equation with source provenance for the term occurrences on each side. */
public final class ExactParsedEquation {
    private final Equation equation;
    private final ExactParsedTerm left;
    private final ExactParsedTerm right;
    private final int leftStart;
    private final int rightStart;

    ExactParsedEquation(ExactParsedTerm left, ExactParsedTerm right, int leftStart, int rightStart) {
        this.equation = new Equation(left.expression(), right.expression());
        this.left = left;
        this.right = right;
        this.leftStart = leftStart;
        this.rightStart = rightStart;
    }

    public Equation equation() {
        return equation;
    }

    /** Resolves the exact node's half-open UTF-16 range in the original equation. */
    public Optional<SourceRange> sourceRangeFor(Expr node) {
        return left.sourceRangeFor(node).map(range -> new SourceRange(leftStart + range.startInclusive(), leftStart + range.endExclusive())).or(() -> right.sourceRangeFor(node)
            .map(range -> new SourceRange(rightStart + range.startInclusive(), rightStart + range.endExclusive())));
    }
}
