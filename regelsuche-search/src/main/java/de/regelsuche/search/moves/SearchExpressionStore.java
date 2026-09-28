package de.regelsuche.search.moves;

import de.regelsuche.ast.Expr;

/** Per-run ownership of immutable expressions. */
public final class SearchExpressionStore implements AutoCloseable {
    public record Limits(long nodes, long characters, long references, int indexEntries) {
        public static final Limits DEFAULT = new Limits(1_000_000, 16_777_216, 2_000_000, 4096);
    }
    public record Statistics(long liveNodes, long liveCharacters, long liveReferences, long peakNodes,
            long peakCharacters, long peakReferences, int indexEntries, long evictions) {}
    public static final class LimitExceeded extends IllegalStateException {
        public LimitExceeded() { super("NATIVE_RETENTION_EXHAUSTED"); }
    }
    public SearchExpressionStore(Limits limits) {}
    public SearchExpressionRef intern(Expr expression) { throw new UnsupportedOperationException("native expression ownership not implemented"); }
    public Expr dereference(SearchExpressionRef reference) { throw new UnsupportedOperationException("native expression ownership not implemented"); }
    public Statistics statistics() { throw new UnsupportedOperationException("native expression ownership not implemented"); }
    @Override public void close() {}
}
