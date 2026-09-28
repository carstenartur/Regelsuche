package de.regelsuche.search.moves;

import de.regelsuche.ast.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Per-run ownership. Optional FIFO index eviction never releases a still-owned expression. */
public final class SearchExpressionStore implements AutoCloseable,de.regelsuche.retention.RetainedGraph.View {
    @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v){v.reference(limits);v.reference(index);v.reference(roots);v.reference(nodes);v.reference(textValues);}
    public record Limits(long nodes, long characters, long references, int indexEntries) implements de.regelsuche.retention.RetainedGraph.View {
        @Override public void retainedReferences(de.regelsuche.retention.RetainedGraph.Visitor v) {}
        public static final Limits DEFAULT = new Limits(1_000_000, 16_777_216, 2_000_000, 4096);
        public Limits {
            if (nodes < 1 || characters < 1 || references < 1 || indexEntries < 0)
                throw new IllegalArgumentException("positive finite ownership limits required");
        }
    }
    public record Statistics(long liveNodes, long liveCharacters, long liveReferences, long peakNodes,
            long peakCharacters, long peakReferences, int indexEntries, long evictions) {}
    public static final class LimitExceeded extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;
        public LimitExceeded() { super("NATIVE_RETENTION_EXHAUSTED"); }
    }
    private final Limits limits;
    private final LinkedHashMap<Expr, SearchExpressionRef> index = new LinkedHashMap<>();
    private final ArrayList<SearchExpressionRef> roots = new ArrayList<>();
    private final IdentityHashMap<Expr, Boolean> nodes = new IdentityHashMap<>();
    private final IdentityHashMap<Object,Boolean> textValues=new IdentityHashMap<>();
    private long characters, references, peakNodes, peakCharacters, peakReferences, evictions;
    private boolean closed;
    private long work;
    public long work(){return work;}
    private void pay(long units){work=Math.addExact(work,units);}

    public SearchExpressionStore(Limits limits) { this.limits = Objects.requireNonNull(limits); }

    public SearchExpressionRef intern(Expr expression) {
        requireOpen(); Objects.requireNonNull(expression);pay(1);
        var existing = index.get(expression); // HashMap confirms full immutable Expr equality after hashing.
        if (existing != null) return existing;
        var added = new IdentityHashMap<Expr, Boolean>();
        var addedText=new IdentityHashMap<Object,Boolean>();
        var pending = new ArrayDeque<Expr>(); pending.push(expression);pay(1);
        try(var retained=de.regelsuche.retention.RetainedOperation.retain(this,expression,added,addedText,pending)) {
        long addedCharacters = 0, addedReferences = 2; // owned root slot and reference -> expression
        while (!pending.isEmpty()) {
            Expr node = pending.pop();pay(1);
            pay(1);if(nodes.containsKey(node))continue;
            pay(1);if(added.put(node,Boolean.TRUE)!=null)continue;
            addedReferences = Math.addExact(addedReferences, 2); // ownership-index key/value
            switch (node) {
                case BinaryExpr binary -> {
                    pending.push(binary.left()); pending.push(binary.right());pay(2);
                    addedReferences = Math.addExact(addedReferences, 3); // children and operator
                }
                case FunctionExpr function -> {
                    pending.addAll(function.arguments());pay(function.arguments().size());
                    addedCharacters = Math.addExact(addedCharacters, text(function.name(),addedText));
                    addedReferences = Math.addExact(addedReferences, 2L + function.arguments().size());
                }
                case VariableExpr variable -> {
                    addedCharacters = Math.addExact(addedCharacters, text(variable.name(),addedText));
                    addedReferences = Math.addExact(addedReferences, 2);
                }
                case NumberExpr number -> {
                    addedCharacters = Math.addExact(addedCharacters, Math.addExact(text(number.value().numerator(),addedText),text(number.value().denominator(),addedText)));
                    addedReferences = Math.addExact(addedReferences, 3);
                }
            }
            de.regelsuche.retention.RetainedOperation.checkpoint();
        }
        addedReferences=Math.addExact(addedReferences,2L*addedText.size());
        int nextIndexSize = Math.min(limits.indexEntries(), index.size() + 1);
        long nextReferences = Math.addExact(references, addedReferences);
        long nextCharacters = Math.addExact(characters, addedCharacters);
        long nextNodes = Math.addExact(nodes.size(), added.size());
        long withIndex = Math.addExact(nextReferences, 2L * nextIndexSize);
        if (nextNodes > limits.nodes() || nextCharacters > limits.characters() || withIndex > limits.references()) throw new LimitExceeded();
        // Validation/arithmetic precedes mutation, including rejection under total live retention pressure.
        var reference = new SearchExpressionRef(this, expression);
        pay(Math.addExact(Math.addExact(added.size(),addedText.size()),1));nodes.putAll(added);textValues.putAll(addedText); roots.add(reference); references = nextReferences; characters = nextCharacters;
        if (limits.indexEntries() > 0) {
            if (index.size() == limits.indexEntries()) {
                pay(1);index.remove(index.keySet().iterator().next()); evictions = Math.addExact(evictions, 1);
            }
            pay(1);index.put(expression, reference);
        }
        peakNodes = Math.max(peakNodes, nextNodes); peakCharacters = Math.max(peakCharacters, characters);
        peakReferences = Math.max(peakReferences, withIndex);
        de.regelsuche.retention.RetainedOperation.checkpoint();
        return reference;
        } finally {pay(Math.addExact(2L*added.size(),Math.addExact(2L*addedText.size(),pending.size())));added.clear();addedText.clear();pending.clear();}
    }
    private long text(Object value,IdentityHashMap<Object,Boolean> added) {
        pay(1);if(textValues.containsKey(value))return 0;
        pay(1);if(added.put(value,Boolean.TRUE)!=null)return 0;
        if(value instanceof String string)return string.length();
        var integer=(java.math.BigInteger)value;
        if(integer.getClass()!=java.math.BigInteger.class)throw new IllegalArgumentException("unsupported scalar retention subclass");
        String decimal=integer.toString();pay(Math.addExact(decimal.length(),2));
        try(var retained=de.regelsuche.retention.RetainedOperation.retain(decimal)){return decimal.length();}
    }
    public Expr dereference(SearchExpressionRef reference) {
        requireOpen();pay(1);
        if (reference == null || reference.owner != this) throw new IllegalArgumentException("foreign expression session reference");
        return reference.expression;
    }
    public Statistics statistics() {
        return new Statistics(nodes.size(), characters, Math.addExact(references, 2L * index.size()),
            peakNodes, peakCharacters, peakReferences, index.size(), evictions);
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("expression session is closed"); }
    @Override public void close() { if(closed)return;pay(Math.addExact(roots.size(),Math.addExact(2L*index.size(),Math.addExact(2L*nodes.size(),2L*textValues.size()))));closed = true; index.clear(); roots.clear(); nodes.clear();textValues.clear(); characters = 0; references = 0; }
}
