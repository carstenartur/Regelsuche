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
    // Integer keys keep recursive Expr.hashCode/equals outside collection internals.
    private final LinkedHashMap<Integer, ArrayList<SearchExpressionRef>> index = new LinkedHashMap<>();
    private final ArrayList<SearchExpressionRef> roots = new ArrayList<>();
    private final IdentityHashMap<Expr, Boolean> nodes = new IdentityHashMap<>();
    private final IdentityHashMap<Object,Boolean> textValues=new IdentityHashMap<>();
    private long characters, references, peakNodes, peakCharacters, peakReferences, evictions;
    private boolean closed;
    private long work;
    private int indexEntries;
    public long work(){return work;}
    void pay(long units){work=Math.addExact(work,units);}
    void checkIndexScratch(long references, long characters) {
        pay(1);
        if (references > limits.references() || characters > limits.characters()) throw new LimitExceeded();
    }

    public SearchExpressionStore(Limits limits) { this.limits = Objects.requireNonNull(limits); }

    public SearchExpressionRef intern(Expr expression) {
        requireOpen(); Objects.requireNonNull(expression);pay(1);
        int hash = 0;
        if (limits.indexEntries() > 0) {
            hash = SearchExpressionIdentity.hash(this, expression);
            var existing = findIndexed(hash, expression);
            if (existing != null) return existing;
        }
        var added = new IdentityHashMap<Expr, Boolean>();
        var addedText=new IdentityHashMap<Object,Boolean>();
        var pending = new ArrayDeque<Expr>(); pending.push(expression);pay(1);
        long previousReferences = references, previousCharacters = characters;
        long previousPeakNodes = peakNodes, previousPeakCharacters = peakCharacters, previousPeakReferences = peakReferences;
        long previousEvictions = evictions;
        SearchExpressionRef committed = null, evicted = null;
        Throwable primary = null;
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
        evicted = oldestIndexedRoot();
        long nextReferences = Math.addExact(references, addedReferences);
        long nextCharacters = Math.addExact(characters, addedCharacters);
        long nextNodes = Math.addExact(nodes.size(), added.size());
        long withIndex = Math.addExact(nextReferences, indexReferencesAfterInsertion(hash, evicted));
        if (nextNodes > limits.nodes() || nextCharacters > limits.characters() || withIndex > limits.references()) throw new LimitExceeded();
        // Validation/arithmetic precedes mutation, including rejection under total live retention pressure.
        var reference = new SearchExpressionRef(this, expression, hash);
        pay(Math.addExact(Math.addExact(added.size(),addedText.size()),1));nodes.putAll(added);textValues.putAll(addedText); roots.add(reference); references = nextReferences; characters = nextCharacters;
        index(reference, evicted);
        committed = reference;
        peakNodes = Math.max(peakNodes, nextNodes); peakCharacters = Math.max(peakCharacters, characters);
        peakReferences = Math.max(peakReferences, withIndex);
        de.regelsuche.retention.RetainedOperation.checkpoint();
        return reference;
        } catch (RuntimeException | Error failure) {
            primary = failure;
            if (committed != null) {
                var rollbackReference = committed;
                var evictedReference = evicted;
                attemptCleanup(failure, () -> rollbackIndex(rollbackReference, evictedReference));
                attemptCleanup(failure, roots::removeLast);
                for (var node : added.keySet()) attemptCleanup(failure, () -> nodes.remove(node));
                for (var text : addedText.keySet()) attemptCleanup(failure, () -> textValues.remove(text));
                references = previousReferences; characters = previousCharacters;
                peakNodes = previousPeakNodes; peakCharacters = previousPeakCharacters; peakReferences = previousPeakReferences;
                evictions = previousEvictions;
                attemptCleanup(failure, () -> pay(Math.addExact(Math.addExact(added.size(), addedText.size()), 1)));
            }
            throw failure;
        } finally {
            long cleanupWork = Math.addExact(2L*added.size(),Math.addExact(2L*addedText.size(),pending.size()));
            if (primary == null) {
                try { pay(cleanupWork); }
                catch (RuntimeException | Error failure) {
                    added.clear(); addedText.clear(); pending.clear();
                    throw failure;
                }
            } else attemptCleanup(primary, () -> pay(cleanupWork));
            added.clear(); addedText.clear(); pending.clear();
        }
    }
    private static void attemptCleanup(Throwable primary, Runnable cleanup) {
        try { cleanup.run(); }
        catch (RuntimeException | Error failure) {
            if (failure != primary) primary.addSuppressed(failure);
        }
    }
    private SearchExpressionRef findIndexed(int hash, Expr expression) {
        var bucket = index.get(hash); pay(1);
        if (bucket == null) return null;
        for (int i = 0; i < bucket.size(); i++) {
            var existing = bucket.get(i); pay(1);
            if (SearchExpressionIdentity.same(this, expression, existing.expression)) return existing;
        }
        return null;
    }
    private SearchExpressionRef oldestIndexedRoot() {
        return indexEntries > 0 && indexEntries == limits.indexEntries()
            ? roots.get(roots.size() - indexEntries) : null;
    }
    private long indexReferencesAfterInsertion(int hash, SearchExpressionRef evicted) {
        if (limits.indexEntries() == 0) return 0;
        int nextIndexSize = Math.min(limits.indexEntries(), indexEntries + 1);
        int nextBuckets = index.size();
        pay(1); if (!index.containsKey(hash)) nextBuckets++;
        if (evicted != null && evicted.structuralHash != hash) {
            pay(1); if (index.get(evicted.structuralHash).size() == 1) nextBuckets--;
        }
        return indexReferences(nextIndexSize, nextBuckets);
    }
    private void index(SearchExpressionRef reference, SearchExpressionRef evicted) {
        if (limits.indexEntries() == 0) return;
        if (evicted != null) {
            var bucket = index.get(evicted.structuralHash); pay(1);
            pay(bucket.size()); bucket.remove(0); // FIFO within a collision bucket, including shifted slots
            if (bucket.isEmpty()) { index.remove(evicted.structuralHash); pay(1); }
            indexEntries--; evictions = Math.addExact(evictions, 1);
        }
        var bucket = index.get(reference.structuralHash); pay(1);
        if (bucket == null) { bucket = new ArrayList<>(); index.put(reference.structuralHash, bucket); pay(2); }
        bucket.add(reference); indexEntries++; pay(1);
    }
    private void rollbackIndex(SearchExpressionRef reference, SearchExpressionRef evicted) {
        if (limits.indexEntries() == 0) return;
        var bucket = index.get(reference.structuralHash);
        bucket.removeLast(); indexEntries--;
        if (bucket.isEmpty()) index.remove(reference.structuralHash);
        long rollbackWork = 3;
        if (evicted != null) {
            var oldestBucket = index.get(evicted.structuralHash);
            if (oldestBucket == null) {
                oldestBucket = new ArrayList<>(); index.put(evicted.structuralHash, oldestBucket);
                rollbackWork += 2;
            }
            rollbackWork = Math.addExact(rollbackWork, oldestBucket.size() + 2L);
            oldestBucket.addFirst(evicted); indexEntries++; evictions--;
        }
        pay(rollbackWork);
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
        return new Statistics(nodes.size(), characters, Math.addExact(references, indexReferences(indexEntries, index.size())),
            peakNodes, peakCharacters, peakReferences, indexEntries, evictions);
    }
    private static long indexReferences(int entries, int buckets) { return Math.addExact(entries, 3L * buckets); } // key/value, bucket backing slot, members
    private void requireOpen() { if (closed) throw new IllegalStateException("expression session is closed"); }
    @Override public void close() { if(closed)return;pay(Math.addExact(roots.size(),Math.addExact(indexReferences(indexEntries,index.size()),Math.addExact(2L*nodes.size(),2L*textValues.size()))));closed = true; index.clear(); indexEntries=0; roots.clear(); nodes.clear();textValues.clear(); characters = 0; references = 0; }
}
