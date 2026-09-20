package de.regelsuche.search.moves;

import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Opt-in, caller-owned primitive candidate reuse. Create a fresh instance for each
 * independently measured search. This caches generation, never proof authorization.
 * The descriptor, rule inventory and transport bounds must remain fixed per instance.
 */
public final class TypedPrimitiveCandidateCache implements TypedMoveSearch.TypedProvider {
    public record Statistics(long hits, long misses, long bypasses, int entries, long retainedCharacters) {}
    private record Key(String expression, List<String> assumptions, MoveContext context) {
        private Key { assumptions = List.copyOf(assumptions); }
    }
    private record Entry(Batch batch, long characters) {}
    private record Footprint(long characters, long inspections) {}

    private final TypedMoveSearch.TypedProvider delegate;
    private final int capacity;
    private final long maximumCharacters;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    private long hits;
    private long misses;
    private long bypasses;
    private long retainedCharacters;

    /** Checked convenience constructor for concrete immutable pattern rules. */
    public TypedPrimitiveCandidateCache(Descriptor descriptor, List<PatternRewriteRule> rules,
            int maximumGrowth, int maximumCandidates, int capacity, long maximumCharacters) {
        this(descriptor, patternTransport(rules, maximumGrowth, maximumCandidates), capacity, maximumCharacters);
    }

    /**
     * Explicit integration boundary for a caller-owned deterministic transport.
     * The caller must guarantee identical complete step metadata and order for
     * equal source ASTs throughout the session. Mutable, random, history-dependent
     * or externally reconfigured rules violate this contract and must not use it.
     * No proof is trusted through this factory: verification still regenerates.
     */
    public static TypedPrimitiveCandidateCache forDeterministicTransport(Descriptor descriptor,
            AstRewriteTransport transport, int capacity, long maximumCharacters) {
        return new TypedPrimitiveCandidateCache(descriptor, transport, capacity, maximumCharacters);
    }

    private TypedPrimitiveCandidateCache(Descriptor descriptor, AstRewriteTransport transport,
            int capacity, long maximumCharacters) {
        if (capacity < 0 || maximumCharacters < 0) throw new IllegalArgumentException("negative retention bound");
        delegate = TypedMoveSearch.primitiveProvider(descriptor, Objects.requireNonNull(transport, "transport"));
        this.capacity = capacity;
        this.maximumCharacters = maximumCharacters;
    }

    private static AstRewriteTransport patternTransport(List<PatternRewriteRule> rules, int growth, int candidates) {
        var retainedRules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        if (retainedRules.stream().anyMatch(rule -> rule.getClass() != PatternRewriteRule.class)) {
            throw new IllegalArgumentException("only immutable concrete pattern rules are cacheable");
        }
        return new AstRewriteTransport(List.copyOf(retainedRules), growth, candidates);
    }

    @Override public Descriptor descriptor() { return delegate.descriptor(); }

    @Override public synchronized Batch candidates(MoveState state, MoveContext context) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(context, "context");
        if (capacity == 0 || maximumCharacters == 0) {
            bypasses++;
            return delegate.candidates(state, context);
        }
        // This primitive delegate reads only the expression and these premises.
        // Keep phase/goal as additional isolation. Depth/history/capabilities remain
        // untouched in MoveSearch state identity, scheduling and edge admission.
        var key = new Key(state.expression(), state.assumptions(), context);
        var retained = entries.get(key);
        if (retained != null) {
            hits++;
            var original = retained.batch();
            var work = TransformationWorkMetrics.ZERO
                .withCandidateWork(original.work().candidateWork())
                .withDelegatedMechanicalWork(1L + original.moves().size());
            return new Batch(original.moves(), work, original.complete());
        }
        misses++;
        var generated = delegate.candidates(state, context);
        var footprint = footprint(key, generated);
        long overhead = Math.addExact(1, footprint.inspections());
        if (footprint.characters() > maximumCharacters) {
            bypasses++;
            return charged(generated, overhead);
        }
        // FIFO is deterministic. Eviction affects reuse only, never candidate admission.
        while (entries.size() >= capacity || footprint.characters() > maximumCharacters - retainedCharacters) {
            var iterator = entries.entrySet().iterator();
            var oldest = iterator.next();
            retainedCharacters -= oldest.getValue().characters();
            iterator.remove();
            overhead = Math.addExact(overhead, 1);
        }
        entries.put(key, new Entry(generated, footprint.characters()));
        retainedCharacters = Math.addExact(retainedCharacters, footprint.characters());
        return charged(generated, Math.addExact(overhead, 1));
    }

    public synchronized Statistics statistics() {
        return new Statistics(hits, misses, bypasses, entries.size(), retainedCharacters);
    }

    private static Batch charged(Batch original, long overhead) {
        return new Batch(original.moves(), original.work().plus(
            TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(overhead)), original.complete());
    }

    /** Bound retained variable text, not JVM heap bytes. Fixed inventory metadata is shared. */
    private static Footprint footprint(Key key, Batch batch) {
        long characters = Math.addExact(key.expression().length(), key.context().goal().length());
        long inspections = 2;
        for (var premise : key.assumptions()) {
            characters = Math.addExact(characters, premise.length());
            inspections++;
        }
        for (var premise : key.context().initialAssumptions()) {
            characters = Math.addExact(characters, premise.length());
            inspections++;
        }
        for (var move : batch.moves()) {
            var step = move.transformation();
            characters = Math.addExact(characters, step.transformedExpression().length());
            characters = Math.addExact(characters, step.applicationKey().length());
            inspections = Math.addExact(inspections, 2);
            // Generated premises are variable retained text too. Count both the
            // producer and normalized move forms conservatively, even if shared.
            for (var premise : step.assumptions()) {
                characters = Math.addExact(characters, premise.length());
                inspections = Math.addExact(inspections, 1);
            }
            for (var premise : move.assumptions()) {
                characters = Math.addExact(characters, premise.length());
                inspections = Math.addExact(inspections, 1);
            }
        }
        return new Footprint(characters, inspections);
    }
}
