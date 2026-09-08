package de.regelsuche.search.program;

import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.json.JsonWriter;
import de.regelsuche.parse.ExactParsedSubtermProjector;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactFactorizationExpressionRenderer;
import de.regelsuche.polynomial.ExactFactorizationTransformationPipeline;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.ExactParsedUnivariatePolynomialView;
import de.regelsuche.polynomial.ExactRationalField;
import de.regelsuche.polynomial.FactorizationEngine;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore;
import de.regelsuche.scalar.ExactRational;
import de.regelsuche.transform.PolynomialTheorySubsumptionClassifier;
import de.regelsuche.transform.ExactTheoryEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Explicit on-demand exact source for the budgeted search/program frontier.
 * The engine is injected; cache misses use that same engine, never a hidden
 * alternative. The cache index is merely an exact source lookup: only the
 * store's live replay and the shared occurrence verifier can release an edge.
 */
public final class ExactPolynomialTransformationSource implements BudgetedTransformationSource {
    public static final String SOURCE_ID = "regelsuche.exact-polynomial-transformation-source/v1";
    public enum Mode { ON_DEMAND, VERIFIED_CACHE }

    private final FactorizationEngine<ExactRational> engine;
    private final Mode mode;
    private final List<Integer> path;
    private final CacheState cacheState;
    private final VerifiedPolynomialTransitionCacheStore cache;
    private final Map<String, IndexedEntry> index;
    private final SourceIdentity identity;
    private Optional<Observation> lastObservation = Optional.empty();
    private PolynomialWorkLedger lastWork = PolynomialWorkLedger.empty();
    private Optional<VerifiedExecution> lastVerifiedExecution = Optional.empty();

    public ExactPolynomialTransformationSource(FactorizationEngine<ExactRational> engine,
            Mode mode, List<Integer> path, int cacheCapacity) {
        this(engine, mode, path, new CacheState(cacheCapacity));
    }

    private ExactPolynomialTransformationSource(FactorizationEngine<ExactRational> engine,
            Mode mode, List<Integer> path, CacheState cacheState) {
        this.engine = Objects.requireNonNull(engine, "engine");
        if (!ExactRationalField.DOMAIN_ID.equals(engine.coefficientDomainId())) {
            throw new IllegalArgumentException("exact search requires a rational polynomial engine");
        }
        this.mode = Objects.requireNonNull(mode, "mode");
        this.path = List.copyOf(path);
        if (this.path.size() > 256 || this.path.stream().anyMatch(i -> i < 0)) {
            throw new IllegalArgumentException("invalid polynomial occurrence path");
        }
        this.cacheState = cacheState;
        cache = cacheState.store;
        index = cacheState.index;
        String revision = hash(SOURCE_ID, ExactNestedFactorizationTransformationPipeline.PIPELINE_ID,
            engine.engineId(), engine.coefficientDomainId(), mode.name(), this.path.toString(),
            Integer.toString(cache.capacity()), "exact-source-fifo:no-backend-fallback:raw-work-ceiling");
        identity = new SourceIdentity(SOURCE_ID, revision, revision);
    }

    @Override public SourceIdentity identity() { return identity; }
    public synchronized Optional<Observation> lastObservation() { return lastObservation; }
    public synchronized PolynomialWorkLedger lastWork() { return lastWork; }
    public synchronized Optional<VerifiedExecution> lastVerifiedExecution() { return lastVerifiedExecution; }
    public synchronized VerifiedPolynomialTransitionCacheStore.Stats cacheStats() {
        synchronized (cacheState) {
            var stats = cache.stats();
            return new VerifiedPolynomialTransitionCacheStore.Stats(stats.retainedEntries(),
                stats.insertions(), stats.hits(), cacheState.indexMisses,
                stats.evictions(), stats.replays());
        }
    }

    /** Another occurrence in the same explicitly owned run/cache lifetime. */
    public ExactPolynomialTransformationSource atPath(List<Integer> selectedPath) {
        return new ExactPolynomialTransformationSource(engine, mode, selectedPath, cacheState);
    }

    /** Explicit learning handoff, admitted separately and never added to the rule inventory. */
    public synchronized PolynomialWorkLedger retainLearned(
            PolynomialTheorySubsumptionClassifier.Classification classification,
            List<String> provenance, long availableMechanicalWork) {
        if (mode != Mode.VERIFIED_CACHE) throw new IllegalStateException("cache is disabled");
        var authorization = Objects.requireNonNull(classification, "classification").transformation().orElseThrow(
            () -> new IllegalArgumentException("learning handoff lacks verifier authorization"));
        if (!engine.engineId().equals(authorization.factorization().engineId())) {
            throw new IllegalArgumentException("learned transformation uses another engine");
        }
        synchronized (cacheState) {
            Work work = new Work(availableMechanicalWork);
            try { retain(authorization, provenance, work); }
            catch (Exhausted exhausted) {
                throw new IllegalArgumentException("insufficient work to retain learned polynomial evidence");
            }
            return work.ledger();
        }
    }

    @Override public synchronized Result transform(String expression, long availableMathematicalWorkUnits) {
        synchronized (cacheState) {
            return executeSource(expression, availableMathematicalWorkUnits);
        }
    }

    private Result executeSource(String expression, long availableMathematicalWorkUnits) {
        if (expression == null || expression.isBlank() || availableMathematicalWorkUnits < 0
                || availableMathematicalWorkUnits == Long.MAX_VALUE) {
            throw new IllegalArgumentException("exact source and nonnegative work authority are required");
        }
        lastObservation = Optional.empty();
        lastWork = PolynomialWorkLedger.empty();
        lastVerifiedExecution = Optional.empty();
        long ceiling = Math.min(availableMathematicalWorkUnits,
            ExactNestedFactorizationTransformationPipeline.Policy.boundedDefaults().maxTotalWorkUnits());
        Work work = new Work(ceiling);
        try {
            if (expression.length() > ExactParsedSubtermProjector.MAX_ROOT_SOURCE_CODE_UNITS) throw new Exhausted();
            work.add("projection.search-source-parse-code-units", expression.length());
            ExactParsedTerm root;
            try { root = new ExpressionParser().parseExactTerm(expression); }
            catch (IllegalArgumentException invalid) {
                return noMatch(expression, availableMathematicalWorkUnits, work, "UNSUPPORTED_EXACT_SOURCE");
            }
            TreePosition selector = new TreePosition(path, "pending");
            work.add("projection.search-path-navigation", path.size() + 1L);
            var selected = selector.subtreeAt(root.expression());
            if (selected.isEmpty()) return noMatch(expression, availableMathematicalWorkUnits, work, "POSITION_NOT_PRESENT");
            var range = root.sourceRangeFor(selected.orElseThrow()).orElseThrow();
            work.add("projection.search-source-snapshot-code-units", range.endExclusive() - range.startInclusive());
            String source = expression.substring(range.startInclusive(), range.endExclusive());
            work.add("projection.search-staleness-format-reserve", Math.multiplyExact(4L, source.length()));
            TreePosition position = new TreePosition(path, ExpressionFormatter.format(selected.orElseThrow()));
            return execute(expression, root, position, source, range, availableMathematicalWorkUnits, work);
        } catch (Exhausted exhausted) {
            return inconclusive(expression, availableMathematicalWorkUnits, work, "POLYNOMIAL_SOURCE_WORK_EXHAUSTED");
        } finally {
            // Technical failures propagate, but never erase the consumed prefix.
            lastWork = work.ledger();
        }
    }

    private Result execute(String expression, ExactParsedTerm root, TreePosition position,
            String source, ExactParsedTerm.SourceRange range, long available, Work work) {
        VerifiedPolynomialTransitionCacheStore.ReplayResult released = null;
        if (mode == Mode.VERIFIED_CACHE) {
            work.add("cache.lookup.exact-source-index-code-units", source.length() + 1L);
            var indexed = index.get(source);
            if (indexed == null) {
                cacheState.indexMisses = Math.incrementExact(cacheState.indexMisses);
            } else {
                var request = indexed.request();
                // Reserve all bounded key comparison and release work before either operation.
                long lookupCeiling = request.cacheId().length() + request.cacheRevision().length()
                    + request.sourceEvidenceHash().length() + request.sourceExpression().length() + 2L;
                work.require(lookupCeiling);
                var lookup = cache.lookup(request);
                work.merge("cache.lookup.", lookup.lookupWork());
                if (indexed.mathematicalWork() > available) {
                    return inconclusive(expression, available, work, "PRIMITIVE_MATHEMATICAL_AUTHORITY_INSUFFICIENT");
                }
                long releaseCeiling = cache.replayWorkCeiling(lookup);
                work.require(releaseCeiling);
                released = cache.replay(lookup);
                work.merge("cache.replay.", released.replayWork());
                if (!released.replayed()) throw new IllegalStateException("owned cache index lost its retention binding");
            }
        }
        if (work.remaining() < 1) throw new Exhausted();
        var pipeline = pipeline(work.remaining());
        var nested = released == null ? pipeline.transform(root, position, engine, 0)
            : pipeline.replay(root, position, released.authorization().orElseThrow());
        work.merge("", nested.totalWork());
        lastObservation = Optional.of(new Observation(nested, Optional.ofNullable(released), work.ledger()));
        if (!nested.transformed()) {
            return switch (nested.status()) {
                case BUDGET_INCONCLUSIVE -> inconclusive(expression, available, work, nested.detailCode());
                case TECHNICAL_FAILURE, SOURCE_EVIDENCE_MISMATCH -> throw new IllegalStateException(nested.detailCode());
                default -> noMatch(expression, available, work, nested.detailCode());
            };
        }
        var primitive = nested.transformation().orElseThrow();
        String replacement = primitive.transformedExpression().orElseThrow();
        long outputLength = (long) expression.length() - (range.endExclusive() - range.startInclusive()) + replacement.length() + 2;
        work.add("nested.rewritten-exact-source-code-units", outputLength);
        String rewritten = expression.substring(0, range.startInclusive()) + "(" + replacement + ")"
            + expression.substring(range.endExclusive());
        // The surrounding source is sliced, never rendered through NumberExpr/double.
        new ExpressionParser().parseExactTerm(rewritten);
        long mathematical = Math.max(1L, primitive.factorization().totalWork().totalWorkUnits());
        long evidenceCeiling = Math.addExact(4096L, Math.multiplyExact(8L,
            Math.addExact((long) expression.length(), rewritten.length())));
        work.require(evidenceCeiling);
        String evidenceJson = new JsonWriter().beginObject()
            .property("schema", "regelsuche.verified-polynomial-search-execution/v1")
            .property("source", expression).property("transformed", rewritten)
            .property("primitiveEvidenceHash", primitive.certificateHash())
            .property("occurrenceEvidenceHash", nested.certificateHash())
            .property("theoryStepId", ExactFactorizationTransformationPipeline.TRANSFORMATION_ID)
            .property("mathematicalWorkUnits", mathematical).endObject().toString();
        long evidenceUnits = evidenceJson.getBytes(StandardCharsets.UTF_8).length;
        if (evidenceUnits > evidenceCeiling) throw new IllegalStateException("execution evidence exceeded its admitted ceiling");
        work.add("nested.rewritten-execution-evidence-utf8-bytes", evidenceUnits);
        if (mode == Mode.VERIFIED_CACHE && released == null) retain(primitive, List.of(position.pathKey()), work);
        lastObservation = Optional.of(new Observation(nested, Optional.ofNullable(released), work.ledger()));
        var transition = ExactTheoryTransition.create(expression, rewritten,
            ExactFactorizationTransformationPipeline.TRANSFORMATION_ID, primitive.certificateHash(), List.of(),
            mathematical, nested.certificateHash());
        lastWork = work.ledger();
        var result = Result.candidates(identity, expression, available, List.of(transition), work.total,
            released == null ? "ON_DEMAND_VERIFIED_POLYNOMIAL" : "VERIFIED_POLYNOMIAL_CACHE_REPLAY");
        lastVerifiedExecution = Optional.of(new VerifiedExecution(result, evidenceJson));
        return result;
    }

    private void retain(ExactFactorizationTransformationPipeline.Result primitive, List<String> provenance, Work work) {
        String source = primitive.occurrence().sourceText();
        if (index.containsKey(source)) return;
        long material = source.length() + primitive.transformedExpression().orElseThrow().length()
            + provenance.stream().mapToLong(String::length).sum() + 1024L;
        work.add("cache.insertion.verifier-evidence-code-units", material);
        if (cache.size() == cache.capacity()) work.add("cache.eviction.fifo-entry", 1);
        var retained = cache.retain(primitive, SOURCE_ID, engine.engineId(),
            new VerifiedPolynomialTransitionCacheStore.Observation(primitive.certificateHash(), provenance, List.of()));
        retained.eviction().ifPresent(eviction -> index.values().removeIf(entry ->
            entry.request().keyId().equals(eviction.lookupKeyId())));
        index.put(source, new IndexedEntry(retained.lookupRequest(),
            primitive.factorization().totalWork().totalWorkUnits()));
    }

    private static ExactNestedFactorizationTransformationPipeline pipeline(long work) {
        var defaults = ExactNestedFactorizationTransformationPipeline.Policy.boundedDefaults();
        return new ExactNestedFactorizationTransformationPipeline(new ExactParsedSubtermProjector(),
            new ExactParsedUnivariatePolynomialView(), new ExactFactorizationExpressionRenderer(), new ExpressionParser(),
            new ExactParsedUnivariatePolynomialView(), new ExactNestedFactorizationTransformationPipeline.Policy(
                defaults.maxPathDepth(), defaults.maxRootNodes(), defaults.maxReplacementNodes(), work,
                defaults.structuralLimits(), defaults.maxCandidates(), defaults.evidenceRequirement()));
    }

    private Result inconclusive(String expression, long available, Work work, String detail) {
        lastWork = work.ledger();
        return Result.budgetInconclusive(identity, expression, available, Math.addExact(available, 1L), work.total, detail);
    }
    private Result noMatch(String expression, long available, Work work, String detail) {
        lastWork = work.ledger();
        return Result.noMatch(identity, expression, available, work.total, detail);
    }

    public record Observation(ExactNestedFactorizationTransformationPipeline.Result occurrence,
            Optional<VerifiedPolynomialTransitionCacheStore.ReplayResult> cacheReplay, PolynomialWorkLedger executionWork) {
        public Observation { Objects.requireNonNull(occurrence); Objects.requireNonNull(cacheReplay); Objects.requireNonNull(executionWork); }
    }

    /** Private-constructor capability, issued only after the actual occurrence rewrite succeeds. */
    public static final class VerifiedExecution {
        private final Result result;
        private final String evidenceJson;
        private VerifiedExecution(Result result, String evidenceJson) {
            this.result = result;
            this.evidenceJson = evidenceJson;
        }
        public Result result() { return result; }
        ExactTheoryEvidence.Binding binding() {
            var transition = result.candidates().getFirst();
            return new ExactTheoryEvidence.Binding(transition.sourceExpression(), transition.transformedExpression(),
                transition.theoryStepId(), transition.evidenceHash(), hash(evidenceJson), result.contentHash(),
                transition.mathematicalWorkUnits(), evidenceJson);
        }
    }

    private static final class Work {
        private final long limit;
        private long total;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        Work(long limit) { if (limit < 0) throw new IllegalArgumentException("negative work"); this.limit = limit; }
        long remaining() { return limit - total; }
        void require(long units) { if (units < 0 || units > remaining()) throw new Exhausted(); }
        void add(String stage, long units) { require(units); total += units; stages.merge(stage, units, Math::addExact); }
        void merge(String prefix, PolynomialWorkLedger ledger) { require(ledger.totalWorkUnits()); ledger.stages().forEach((s,n) -> add(prefix+s,n)); }
        PolynomialWorkLedger ledger() { return new PolynomialWorkLedger(stages); }
    }
    private static final class CacheState {
        final VerifiedPolynomialTransitionCacheStore store;
        final Map<String, IndexedEntry> index = new LinkedHashMap<>();
        long indexMisses;
        CacheState(int capacity) { store = new VerifiedPolynomialTransitionCacheStore(capacity); }
    }
    private record IndexedEntry(VerifiedPolynomialTransitionCacheStore.LookupRequest request, long mathematicalWork) { }
    private static final class Exhausted extends RuntimeException { private static final long serialVersionUID = 1L; }
    private static String hash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(bytes);
            }
            return "sha256:"+HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
