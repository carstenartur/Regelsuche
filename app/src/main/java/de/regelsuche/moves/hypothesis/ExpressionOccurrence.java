package de.regelsuche.moves.hypothesis;

import de.regelsuche.ast.Expr;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.value.ExprValue;
import de.regelsuche.value.ValueKey;
import java.util.Comparator;
import java.util.Objects;

/** One concrete syntax use linked to its shared mathematical value. */
public record ExpressionOccurrence(
        OccurrenceId id,
        TreePosition position,
        Expr syntax,
        ExprValue value,
        int depth,
        String parentOperator,
        TermRole role) implements Comparable<ExpressionOccurrence> {

    public static final Comparator<ExpressionOccurrence> CANONICAL_ORDER =
            Comparator.comparing(ExpressionOccurrence::id)
                    .thenComparingInt(occurrence -> occurrence.role().ordinal())
                    .thenComparing(occurrence -> occurrence.value().key());

    public ExpressionOccurrence {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(syntax, "syntax");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(role, "role");
        parentOperator = parentOperator == null ? "" : parentOperator;
        if (depth < 0 || !id.path().equals(position.path())) {
            throw new IllegalArgumentException("invalid occurrence depth or path");
        }
    }

    public ValueKey valueKey() {
        return value.key();
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof ExpressionOccurrence occurrence && id.equals(occurrence.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public int compareTo(ExpressionOccurrence other) {
        return CANONICAL_ORDER.compare(this, other);
    }
}
