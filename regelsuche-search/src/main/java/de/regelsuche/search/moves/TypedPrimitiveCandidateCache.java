package de.regelsuche.search.moves;

import de.regelsuche.transform.AstRewriteTransport;
import de.regelsuche.transform.PatternRewriteRule;
import java.util.List;
import java.util.Objects;

/** Initial uncached control for the candidate-reuse regression tests. */
public final class TypedPrimitiveCandidateCache implements TypedMoveSearch.TypedProvider {
    public record Statistics(long hits, long misses, long bypasses, int entries, long retainedCharacters) {}
    private final TypedMoveSearch.TypedProvider delegate;
    private long misses;

    public TypedPrimitiveCandidateCache(Descriptor descriptor, List<PatternRewriteRule> rules,
            int maximumGrowth, int maximumCandidates, int capacity, long maximumCharacters) {
        Objects.requireNonNull(rules, "rules");
        if (capacity < 0 || maximumCharacters < 0) throw new IllegalArgumentException("negative retention bound");
        if (rules.stream().anyMatch(rule -> rule == null || rule.getClass() != PatternRewriteRule.class)) {
            throw new IllegalArgumentException("only immutable concrete pattern rules are cacheable");
        }
        delegate = TypedMoveSearch.primitiveProvider(descriptor,
            new AstRewriteTransport(List.copyOf(rules), maximumGrowth, maximumCandidates));
    }
    @Override public Descriptor descriptor() { return delegate.descriptor(); }
    @Override public Batch candidates(MoveState state, MoveContext context) {
        misses++;
        return delegate.candidates(state, context);
    }
    public Statistics statistics() { return new Statistics(0, misses, 0, 0, 0); }
}
