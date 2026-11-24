package de.regelsuche.polynomial;

import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.scalar.ExactRational;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A bounded FIFO cache of verifier-issued transformations for one exact engine.
 * A hit supplies the original authority to fresh source checking and replacement
 * in the nested pipeline. The spelling key only selects a proposal for that check.
 */
public final class ExactFactorizationReplaySession {
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_ENTRY_CODE_UNITS = 16_384;

    private final FactorizationEngine<ExactRational> engine;
    private final int capacity;
    private final Map<String, ExactFactorizationTransformationPipeline.Result> authorizations = new LinkedHashMap<>();

    public ExactFactorizationReplaySession(FactorizationEngine<ExactRational> engine, int capacity) {
        this.engine = Objects.requireNonNull(engine);
        if (capacity < 1 || capacity > MAX_ENTRIES) throw new IllegalArgumentException("invalid replay cache capacity");
        this.capacity = capacity;
    }

    public synchronized Execution transform(ExactParsedTerm root, TreePosition position, PolynomialWorkAuthority work) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(position);
        Objects.requireNonNull(work);
        var pipeline = ExactNestedFactorizationTransformationPipeline.withParserRootReuse(work);
        work.consume("cache.lookup.compact-path-navigation", position.path().size() + 1L);
        var range = position.subtreeAt(root.expression()).flatMap(root::sourceRangeFor).orElse(null);
        if (range == null || range.endExclusive() - range.startInclusive() > MAX_ENTRY_CODE_UNITS) {
            return new Execution(pipeline.transform(root, position, engine, 0), Disposition.BYPASSED);
        }
        int sourceLength = range.endExclusive() - range.startInclusive();
        work.consume("cache.lookup.compact-source-key-code-units", sourceLength + 1L);
        String key = range.textFrom(root.source());
        var retained = authorizations.get(key);
        if (retained != null) {
            work.consume("cache.replay.compact-authorization-reads", 1);
            var replayed = pipeline.replay(root, position, retained);
            return new Execution(replayed, replayed.transformed() ? Disposition.REPLAYED : Disposition.REPLAY_REJECTED);
        }

        var result = pipeline.transform(root, position, engine, 0);
        if (!result.transformed()) return new Execution(result, Disposition.MISS);
        var authorization = result.transformation().orElseThrow();
        if (!key.equals(authorization.occurrence().sourceText())
                || authorization.transformedExpression().orElseThrow().length() > MAX_ENTRY_CODE_UNITS) {
            return new Execution(result, Disposition.MISS);
        }
        boolean evict = authorizations.size() == capacity;
        work.consume(new PolynomialWorkLedger(evict ? Map.of(
            "cache.insertion.compact-entry-writes", 1L, "cache.eviction.compact-entry-removals", 1L)
            : Map.of("cache.insertion.compact-entry-writes", 1L)));
        if (evict) authorizations.remove(authorizations.keySet().iterator().next());
        authorizations.put(key, authorization);
        return new Execution(result, Disposition.INSERTED);
    }

    public enum Disposition { BYPASSED, MISS, INSERTED, REPLAYED, REPLAY_REJECTED }

    public record Execution(ExactNestedFactorizationTransformationPipeline.Result result, Disposition disposition) { }
}
