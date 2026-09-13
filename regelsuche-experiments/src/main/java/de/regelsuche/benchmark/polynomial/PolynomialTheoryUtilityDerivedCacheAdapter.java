package de.regelsuche.benchmark.polynomial;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCacheEvent.Kind;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityMeasuredExecution.MeasuredRun;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityTransitionOutcome.CacheDisposition;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExactParsedTerm;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore.RetentionResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native misses and verifier-authorized replay in one empty-at-start run cache. */
public final class PolynomialTheoryUtilityDerivedCacheAdapter implements PolynomialTheoryUtilityProfileAdapter {
    public static final String PROFILE_ID = "VERIFIED_DERIVED_MACRO_CACHE";
    public static final String ADAPTER_ID = "regelsuche.polynomial-theory-utility.verified-derived-macro-cache/v1";
    private static final String REVISION = PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION;
    private static final int PRIMITIVE_STEPS = 7;

    @Override public String profileId() { return PROFILE_ID; }
    @Override public String adapterId() { return ADAPTER_ID; }
    @Override public String resultSchema() { return PolynomialTheoryUtilityCandidateResult.OBSERVED_SCHEMA; }

    @Override public Run openRun(RunDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        var expected = PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(input -> input.runId().equals(descriptor.runId())).toList();
        if (expected.size() != PolynomialTheoryUtilityCaseCorpus.ORDERED_CASE_IDS.size()
                || descriptor.expectedCaseCount() != expected.size()
                || !PROFILE_ID.equals(descriptor.profileId()) || !ADAPTER_ID.equals(descriptor.adapterId())
                || !expected.getFirst().profileId().equals(PROFILE_ID)
                || !expected.getFirst().checkpointId().equals(descriptor.checkpointId())) {
            throw new IllegalArgumentException("cache run differs from the frozen matrix");
        }
        return new CacheRun(expected, descriptor.runId());
    }

    private static final class CacheRun implements MeasuredRun {
        private final List<PolynomialTheoryUtilityExecutionInput> expected;
        private final CacheState cache;
        private final PolynomialTheoryUtilityCacheRunHistory history;
        private int next;
        private boolean closed;
        private CacheRun(List<PolynomialTheoryUtilityExecutionInput> expected, String runId) {
            this.expected = List.copyOf(expected);
            this.cache = new CacheState(runId, PolynomialTheoryUtilityExecutionPlan.CACHE_CAPACITY);
            this.history = new PolynomialTheoryUtilityCacheRunHistory(runId);
        }
        @Override public PolynomialTheoryUtilityMeasuredCandidate executeMeasured(
                PolynomialTheoryUtilityExecutionInput input, PolynomialTheoryUtilityCaseCorpus.FormationCase formation) {
            if (closed || next >= expected.size() || !expected.get(next).equals(input)
                    || !PolynomialTheoryUtilityCaseCorpus.load().cases().get(next).equals(formation)) {
                throw new IllegalArgumentException("cache input is not the next frozen row and formation");
            }
            try {
                var measured = executeCase(input, formation, cache);
                history.accept(measured);
                next++;
                return measured;
            } catch (RuntimeException | Error failure) {
                closed = true;
                throw failure;
            }
        }
        @Override public void close() {
            if (closed || next != expected.size()) throw new IllegalStateException("cache run did not consume every row");
            closed = true;
        }
    }

    private static PolynomialTheoryUtilityMeasuredCandidate executeCase(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation, CacheState cache) {
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        ExactParsedTerm parsed = null;
        try {
            authority.consume("projection.study-source-parse-code-units", formation.sourceExpression().length());
            parsed = new ExpressionParser().parseExactTerm(formation.sourceExpression());
        } catch (PolynomialWorkAuthority.LimitReached exhausted) { /* retain every unexecuted occurrence below */ }
        var preparation = authority.ledger();
        var transitions = new ArrayList<PolynomialTheoryUtilityTransitionOutcome>();
        var traces = new ArrayList<PolynomialTheoryUtilityTransitionTrace>();
        var attempts = new ArrayList<PolynomialTheoryUtilityFactorizationAttempt>();
        var events = new ArrayList<PolynomialTheoryUtilityCacheEvent>();
        var occurrences = new ArrayList<PolynomialTheoryUtilityExecutionObservations.Occurrence>();
        for (var path : PolynomialTheoryUtilityExecutionObservations.paths(formation)) {
            var before = authority.ledger();
            var beforeWork = authority.work();
            var execution = cache.execute(parsed, path, authority);
            var work = PolynomialTheoryUtilityExecutionObservations.difference(authority.work(), beforeWork);
            var transition = execution.status() == TerminalStatus.VALIDATED_TRANSITION
                ? transition(input, formation, execution, work, transitions.size()) : null;
            if (transition != null) {
                transitions.add(transition);
                traces.add(trace(transition, execution.primitiveExpansion()));
            }
            var ownAttempts = new ArrayList<String>();
            if (execution.released() == null && execution.pipeline() != null
                    && execution.pipeline().factorization().filter(value -> value.executed()).isPresent()) {
                var attempt = PolynomialTheoryUtilityFactorizationAttempt.createObserved(attempts.size(), input.inputId(),
                    occurrences.size(), execution.pipeline(), transition == null ? "NONE" : transition.transitionId());
                attempts.add(attempt);
                ownAttempts.add(attempt.attemptId());
            }
            var ownEvents = events(input, execution, transition, events.size());
            events.addAll(ownEvents);
            occurrences.add(new PolynomialTheoryUtilityExecutionObservations.Occurrence(occurrences.size(), path,
                execution.status(), execution.detailCode(),
                execution.pipeline() == null ? "NONE" : execution.pipeline().certificateHash(),
                transition == null ? "NONE" : transition.transitionId(), work.primitiveWork(),
                PolynomialTheoryUtilityExecutionObservations.difference(authority.ledger(), before), ownAttempts,
                ownEvents.stream().map(PolynomialTheoryUtilityCacheEvent::eventId).toList()));
        }
        var observed = PolynomialTheoryUtilityExecutionObservations.create(preparation, occurrences);
        String verifier = switch (observed.terminalStatus()) {
            case VALIDATED_TRANSITION -> "VERIFIED";
            case MIXED_OUTCOMES -> "RETAINED_OCCURRENCE_OUTCOMES";
            default -> attempts.isEmpty() ? "NOT_REQUESTED" : "RETAINED_ATTEMPT_OUTCOMES";
        };
        var result = PolynomialTheoryUtilityCandidateResult.createObserved(input, formation,
            "RUN_OWNED_VERIFIED_CACHE:" + authority.projection().projectionId(), transitions, verifier, observed);
        return PolynomialTheoryUtilityMeasuredCandidate.create(result, traces, attempts, events);
    }

    private static PolynomialTheoryUtilityTransitionTrace trace(PolynomialTheoryUtilityTransitionOutcome transition,
            List<VerifiedPolynomialTransitionCacheStore.PrimitiveStep> expansion) {
        var steps = new ArrayList<PolynomialTheoryUtilityTransitionTrace.PrimitiveStep>();
        for (var step : expansion) {
            steps.add(PolynomialTheoryUtilityTransitionTrace.PrimitiveStep.create(
                transition, steps.size(), 0, step.stageId(), step.evidenceHash()));
        }
        return PolynomialTheoryUtilityTransitionTrace.create(transition, 1, steps, List.of());
    }

    private static PolynomialTheoryUtilityTransitionOutcome transition(PolynomialTheoryUtilityExecutionInput input,
            PolynomialTheoryUtilityCaseCorpus.FormationCase formation, Occurrence execution,
            PolynomialTheoryUtilityWorkBreakdown work, int index) {
        var nested = execution.pipeline();
        var primitive = nested.transformation().orElseThrow();
        String replacement = primitive.transformedExpression().orElseThrow();
        var range = nested.projection().orElseThrow().selectedRange().orElseThrow();
        String root = execution.path().isEmpty() ? replacement : formation.sourceExpression().substring(0, range.startInclusive())
            + "(" + replacement + ")" + formation.sourceExpression().substring(range.endExclusive());
        var retained = execution.retention() == null ? execution.lookup().retained() : execution.retention().retention();
        return PolynomialTheoryUtilityTransitionOutcome.create(index, input.inputId(), execution.path(),
            primitive.occurrence().sourceText(), replacement, formation.sourceExpression(), root,
            PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID, nested.factorization().orElseThrow().engineId(),
            primitive.occurrence().sourceEvidenceHash(), nested.certificateHash(), execution.released() == null
                ? CacheDisposition.CACHE_MISS_INSERTED : CacheDisposition.CACHE_HIT_REPLAYED,
            REVISION, retained.entryId(), execution.retention() == null ? "NONE"
                : retained.eviction().map(value -> value.entryId()).orElse("NONE"), work);
    }

    private static List<PolynomialTheoryUtilityCacheEvent> events(PolynomialTheoryUtilityExecutionInput input,
            Occurrence execution, PolynomialTheoryUtilityTransitionOutcome transition, int start) {
        if (execution.lookup() == null) return List.of();
        var result = new ArrayList<PolynomialTheoryUtilityCacheEvent>();
        String transitionId = transition == null ? "NONE" : transition.transitionId();
        String entryId = transition != null ? transition.cacheEntryId() : execution.lookup().entryId();
        result.add(PolynomialTheoryUtilityCacheEvent.create(start, input.inputId(), transitionId,
            execution.lookup().retained() == null ? Kind.LOOKUP_MISS : Kind.LOOKUP_HIT,
            REVISION, entryId, execution.lookup().evidenceHash()));
        if (execution.retention() != null) {
            var retained = execution.retention().retention();
            result.add(PolynomialTheoryUtilityCacheEvent.create(start + result.size(), input.inputId(), transitionId,
                Kind.INSERTION, REVISION, entryId, retained.certificateHash()));
            retained.eviction().ifPresent(evicted -> result.add(PolynomialTheoryUtilityCacheEvent.create(start + result.size(),
                input.inputId(), transitionId, Kind.EVICTION, REVISION, evicted.entryId(), retained.certificateHash())));
        }
        if (execution.released() != null) {
            result.add(PolynomialTheoryUtilityCacheEvent.createReplayAttempt(start + result.size(), input.inputId(),
                transitionId, REVISION, entryId, execution.released().certificateHash(), execution.status()));
        }
        return List.copyOf(result);
    }

    /** Actual execution state; component callers cannot use it to admit a study result. */
    static final class CacheState {
        private final String runId;
        private final VerifiedPolynomialTransitionCacheStore store;
        private final Map<String, RetentionResult> index = new LinkedHashMap<>();
        CacheState(String runId, int capacity) {
            this.runId = Objects.requireNonNull(runId, "runId");
            store = new VerifiedPolynomialTransitionCacheStore(capacity);
        }
        Occurrence execute(ExactParsedTerm parsed, List<Integer> path, PolynomialTheoryUtilityWorkAuthority authority) {
            ExactNestedFactorizationTransformationPipeline.Result nested = null;
            Lookup lookup = null;
            VerifiedPolynomialTransitionCacheStore.ReplayResult released = null;
            VerifiedPolynomialTransitionCacheStore.MeasuredRetention retained = null;
            String exhaustedDetail = "SHARED_POLYNOMIAL_WORK_AUTHORITY_EXHAUSTED";
            try {
                if (parsed == null || authority.remainingPrimitiveWork() < PRIMITIVE_STEPS) throw new PolynomialWorkAuthority.LimitReached();
                authority.consume("study.evidence.occurrence-records", 1);
                authority.consume("projection.study-path-navigation", path.size() + 1L);
                var selected = new TreePosition(path, "pending").subtreeAt(parsed.expression()).orElseThrow();
                var range = parsed.sourceRangeFor(selected).orElseThrow();
                authority.consume("projection.study-cache-source-snapshot-code-units", range.endExclusive() - range.startInclusive());
                String source = parsed.source().substring(range.startInclusive(), range.endExclusive());
                var position = new TreePosition(path, ExpressionFormatter.formatMeasured(selected,
                    units -> authority.consume("projection.study-position-format-code-units", units)));
                String indexMaterial = "exact-source-index:" + source;
                // Admit the lookup and its complete receipt together, including the larger hit disposition.
                // A refused lookup must not leave cache work whose mandatory event cannot be constructed.
                long lookupCeiling = source.length() + 2L + indexMaterial.getBytes(StandardCharsets.UTF_8).length + 1L;
                lookupCeiling += (runId + ":" + source + ":" + "0".repeat(67) + ":" + "0".repeat(67))
                    .getBytes(StandardCharsets.UTF_8).length + 1L;
                requireOpaque(authority, lookupCeiling);
                authority.consume(new PolynomialWorkLedger(Map.of("cache.lookup.exact-source-index-code-units", source.length() + 1L,
                    "study.evidence.cache-event-records", 1L)));
                var indexed = index.get(source);
                String disposition = indexed == null ? "MISS" : indexed.replayBindingId();
                String entryId = indexed == null ? hash(indexMaterial, authority) : indexed.entryId();
                lookup = new Lookup(indexed, entryId, hash(runId + ":" + source + ":" + entryId
                    + ":" + disposition, authority));
                if (indexed != null) {
                    var exactLookup = store.lookupMeasured(indexed.lookupRequest(), authority).lookup();
                    // Keep the replay event payable after the pre-admitted operation completes.
                    released = store.replayMeasured(exactLookup, reserve(authority, 1L)).replay();
                    authority.consume("study.evidence.cache-event-records", 1);
                    if (!released.replayed()) throw new IllegalStateException("run cache lost an indexed retention generation");
                }
                var pipeline = new ExactNestedFactorizationTransformationPipeline(authority);
                nested = released == null ? pipeline.transform(parsed, position,
                    NativeUnivariateFactorizationEngine.rationals(NativeUnivariateFactorizationPolicy.boundedDefaults()), 0)
                    : pipeline.replay(parsed, position, released.authorization().orElseThrow());
                if (!nested.transformed()) return new Occurrence(path, terminal(nested), nested.detailCode(), nested, lookup, released, null, List.of());
                var primitive = nested.transformation().orElseThrow();
                long output = path.isEmpty() ? primitive.transformedExpression().orElseThrow().length()
                    : parsed.source().length() - (range.endExclusive() - range.startInclusive())
                        + primitive.transformedExpression().orElseThrow().length() + 2L;
                long continuation = output + PRIMITIVE_STEPS + 5L + index.size();
                if (released == null) {
                    exhaustedDetail = "CACHE_RETENTION_NOT_ADMITTED";
                    retained = store.retainMeasured(primitive, runId, REVISION,
                        new VerifiedPolynomialTransitionCacheStore.Observation(primitive.certificateHash(),
                            List.of(position.pathKey()), List.of()), reserve(authority, continuation));
                    if (retained.retention().status() != VerifiedPolynomialTransitionCacheStore.RetentionStatus.INSERTED) {
                        throw new IllegalStateException("native miss did not create a new run entry");
                    }
                    authority.consume("study.evidence.cache-event-records", retained.retention().eviction().isPresent() ? 2 : 1);
                    if (retained.retention().eviction().isPresent()) {
                        authority.consume("cache.eviction.source-index-entry-visits", index.size());
                        String evictedId = retained.retention().eviction().orElseThrow().entryId();
                        index.values().removeIf(value -> value.entryId().equals(evictedId));
                    }
                    authority.consume("cache.insertion.source-index-writes", 1);
                    index.put(source, retained.retention());
                }
                var expansion = retained == null ? released.primitiveExpansion() : retained.primitiveExpansion();
                if (expansion.size() != PRIMITIVE_STEPS) throw new IllegalStateException("shared primitive expansion changed");
                authority.consume(new PolynomialWorkLedger(Map.of("nested.rewritten-exact-source-code-units", output,
                    "study.evidence.transition-outcome-records", 1L, "study.evidence.transition-trace-records", 1L,
                    "study.evidence.primitive-step-records", (long) PRIMITIVE_STEPS)));
                authority.consumePrimitive(PRIMITIVE_STEPS);
                return new Occurrence(path, TerminalStatus.VALIDATED_TRANSITION, "VERIFIED_RUN_CACHE_TRANSITION", nested, lookup, released, retained, expansion);
            } catch (PolynomialWorkAuthority.LimitReached exhausted) {
                if (retained != null) throw new IllegalStateException("retention continuation exceeded its admitted work", exhausted);
                return new Occurrence(path, TerminalStatus.BUDGET_INCONCLUSIVE, exhaustedDetail, nested, lookup, released, null, List.of());
            }
        }
    }

    private static PolynomialWorkAuthority reserve(PolynomialTheoryUtilityWorkAuthority authority, long continuation) {
        return new PolynomialWorkAuthority() {
            @Override public long remainingOpaqueWorkUnits() { return Math.max(0, authority.remainingOpaqueWorkUnits() - continuation); }
            @Override public void consume(PolynomialWorkLedger work) { authority.consume(work); }
        };
    }
    private static void requireOpaque(PolynomialTheoryUtilityWorkAuthority authority, long units) {
        if (authority.remainingOpaqueWorkUnits() < units) throw new PolynomialWorkAuthority.LimitReached();
    }
    private static TerminalStatus terminal(ExactNestedFactorizationTransformationPipeline.Result nested) {
        return switch (nested.status()) {
            case TRANSFORMED -> TerminalStatus.VALIDATED_TRANSITION;
            case BUDGET_INCONCLUSIVE -> TerminalStatus.BUDGET_INCONCLUSIVE;
            case UNSUPPORTED -> TerminalStatus.UNSUPPORTED;
            case TECHNICAL_FAILURE, SOURCE_EVIDENCE_MISMATCH, POSITION_NOT_PRESENT, POSITION_STALE -> TerminalStatus.TECHNICAL_FAILURE;
            default -> TerminalStatus.NO_TRANSITION;
        };
    }
    private static String hash(String source, PolynomialTheoryUtilityWorkAuthority authority) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        authority.consume(new PolynomialWorkLedger(Map.of("cache.lookup.evidence-hash-utf8-bytes", (long) bytes.length,
            "cache.lookup.evidence-hash-completions", 1L)));
        return PolynomialTheoryUtilityExecutionIdentity.sha256(bytes);
    }
    record Lookup(RetentionResult retained, String entryId, String evidenceHash) { }
    record Occurrence(List<Integer> path, TerminalStatus status, String detailCode,
            ExactNestedFactorizationTransformationPipeline.Result pipeline, Lookup lookup,
            VerifiedPolynomialTransitionCacheStore.ReplayResult released,
            VerifiedPolynomialTransitionCacheStore.MeasuredRetention retention,
            List<VerifiedPolynomialTransitionCacheStore.PrimitiveStep> primitiveExpansion) {
        Occurrence { path = List.copyOf(path); primitiveExpansion = List.copyOf(primitiveExpansion); }
    }
}
