package de.regelsuche.benchmarks;

import de.regelsuche.search.strategy.RandomMonteCarloSearchStrategy;
import de.regelsuche.search.strategy.SearchProblem;
import de.regelsuche.search.strategy.SearchState;
import java.util.List;

/**
 * Seeded randomized-valid rewrite control with per-case state reset.
 *
 * <p>The production {@link RandomMonteCarloSearchStrategy} owns mutable random
 * state. Reusing one instance across benchmark cases or repeated bundle runs
 * would therefore make the retained result depend on invocation history. This
 * adapter derives one deterministic case seed from the frozen base seed and the
 * first 64 bits of the source's stable canonical SHA-256 hash, then creates a
 * fresh delegate for every execution.</p>
 */
final class DeterministicRandomValidRewriteStrategy
        implements ComparativeBenchmarkSystems.BenchmarkIdentifiedSearchStrategy {
    static final long DEFAULT_BASE_SEED = 0x235_663L;
    static final String SEED_POLICY =
        "base-seed-xor-first-64-bits-of-stable-source-sha256/v1";

    private final long baseSeed;

    DeterministicRandomValidRewriteStrategy() {
        this(DEFAULT_BASE_SEED);
    }

    DeterministicRandomValidRewriteStrategy(long baseSeed) {
        this.baseSeed = baseSeed;
    }

    @Override
    public List<SearchState> search(SearchProblem problem) {
        String sourceHash = problem.canonicalizer()
            .stableHash(problem.rootExpression());
        if (!sourceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException(
                "stable source hash is not lowercase SHA-256");
        }
        long sourceSalt = Long.parseUnsignedLong(
            sourceHash.substring(0, 16), 16);
        long caseSeed = baseSeed ^ sourceSalt;
        return new RandomMonteCarloSearchStrategy(caseSeed).search(problem);
    }

    @Override
    public String implementationIdentity() {
        return getClass().getName()
            + "\nbaseSeed=" + Long.toUnsignedString(baseSeed)
            + "\nseedPolicy=" + SEED_POLICY
            + "\ndelegate=" + RandomMonteCarloSearchStrategy.class.getName()
            + "\nvalidMoves=production-transformation-engine-only/v1";
    }

    long baseSeed() {
        return baseSeed;
    }
}
