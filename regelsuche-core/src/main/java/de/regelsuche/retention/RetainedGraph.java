package de.regelsuche.retention;

/** Explicit logical ownership graph; implementations describe actual retained object references. */
public final class RetainedGraph {
    private RetainedGraph() {}
    public interface View { void retainedReferences(Visitor visitor); }
    public interface Visitor { void reference(Object value); }
    public record Usage(long nodes,long characters,long references) {}
    public record Observation(Usage retained,Usage peak,long work,long objects) {}
    public static Observation measure(Object root) { throw new UnsupportedOperationException("audited ownership graph not implemented"); }
}
