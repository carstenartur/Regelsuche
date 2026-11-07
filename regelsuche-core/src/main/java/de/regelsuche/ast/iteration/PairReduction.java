package de.regelsuche.ast.iteration;

import de.regelsuche.ast.Expr;
import de.regelsuche.symbol.SymbolId;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Exact sum of a polynomial over all pairs of distinct rows i &lt; j.
 * The two symbols of a column stand for its value in rows i and j respectively.
 */
public record PairReduction(List<Column> columns, Expr summand) {
    public static final int MAX_COLUMNS = 8;
    public static final int MAX_DEGREE = 8;

    public PairReduction {
        Objects.requireNonNull(columns);
        Objects.requireNonNull(summand);
        if (columns.isEmpty() || columns.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("COLUMN_LIMIT");
        }
        columns = List.copyOf(columns);
        var symbols = new HashSet<SymbolId>();
        for (Column column : columns) {
            if (!symbols.add(column.left()) || !symbols.add(column.right())) {
                throw new IllegalArgumentException("DUPLICATE_COLUMN_SYMBOL");
            }
        }
        IterationScope.validate(summand, symbols);
    }

    public record Column(SymbolId left, SymbolId right) {
        public Column {
            Objects.requireNonNull(left);
            Objects.requireNonNull(right);
        }
    }

    /** Number of evaluations of the summand in the direct triangular loop. */
    public static BigInteger originalPairs(long rows) {
        if (rows < 0) {
            throw new IllegalArgumentException("NEGATIVE_ROW_COUNT");
        }
        return BigInteger.valueOf(rows).multiply(BigInteger.valueOf(rows - 1)).shiftRight(1);
    }
}
