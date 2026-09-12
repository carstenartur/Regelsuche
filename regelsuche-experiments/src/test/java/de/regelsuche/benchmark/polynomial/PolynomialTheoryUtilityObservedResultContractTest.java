package de.regelsuche.benchmark.polynomial;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCacheEvent.Kind;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityCandidateResult.TerminalStatus;
import de.regelsuche.benchmark.polynomial.PolynomialTheoryUtilityExecutionObservations.Occurrence;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationEngine;
import de.regelsuche.math.algorithms.polynomial.NativeUnivariateFactorizationPolicy;
import de.regelsuche.moves.enumerate.TreePosition;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.polynomial.ExactNestedFactorizationTransformationPipeline;
import de.regelsuche.polynomial.PolynomialWorkLedger;
import de.regelsuche.polynomial.PolynomialWorkAuthority;
import de.regelsuche.polynomial.VerifiedPolynomialTransitionCacheStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolynomialTheoryUtilityObservedResultContractTest {
    @Test
    void retainsActualSuccessfulAndInterruptedReplaysInOneFrozenRow() {
        var measured = mixedReplay();
        var result = measured.result();
        assertEquals(TerminalStatus.MIXED_OUTCOMES, result.terminalStatus());
        assertFalse(result.transitions().isEmpty());
        assertTrue(result.transitions().size() < result.observations().occurrences().size());
        assertEquals("RETAINED_OCCURRENCE_OUTCOMES", result.verifierOutcome());
        assertTrue(result.observations().occurrences().stream()
            .anyMatch(value -> value.terminalStatus() == TerminalStatus.BUDGET_INCONCLUSIVE));
        assertEquals(result.transitions().size(), measured.measurements().generatedTransitionCount());
        assertEquals(result.work(), PolynomialTheoryUtilityCanonicalWorkProjection
            .project(result.input(), result.observations().rawWork()).work());
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateResult.create(
            result.input(), formation(result.input().caseId()), TerminalStatus.BUDGET_INCONCLUSIVE,
            result.detailCode(), result.work(), result.transitions(), "RETAINED_OCCURRENCE_OUTCOMES"));
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateResult.createObserved(
            result.input(), formation(result.input().caseId()), result.detailCode(), result.transitions(),
            "VERIFIED", result.observations()));
    }

    @Test
    void retainsAnActualInterruptedReplayWithEveryNegativeOccurrenceAndItsConsumedWork() {
        var fixture = interruptedReplay();
        var measured = fixture.measured();
        var result = measured.result();
        assertEquals("regelsuche.polynomial-theory-utility-candidate-result/v3", result.schema());
        assertEquals("regelsuche.polynomial-theory-utility-candidate-measurements/v2", measured.measurements().schema());
        assertEquals(TerminalStatus.BUDGET_INCONCLUSIVE, result.terminalStatus());
        assertEquals(List.of(), result.transitions());
        assertEquals(fixture.rawWork(), result.observations().rawWork().totalMechanicalWork());
        assertEquals(fixture.work(), result.work());
        assertEquals(4, result.observations().occurrences().size());
        assertEquals(1, measured.measurements().cacheHitCount());
        assertEquals(1, measured.measurements().cacheReplayCount());
        assertEquals(TerminalStatus.BUDGET_INCONCLUSIVE, measured.measurements().cacheEvents().getLast().replayOutcome());
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateMeasurements.create(
            result, List.of(), List.of(), List.of(measured.measurements().cacheEvents().getFirst())));
        // The frozen historical producer still refuses this observation; no v2 receipt is reinterpreted.
        assertThrows(IllegalArgumentException.class, () -> PolynomialTheoryUtilityCandidateResult.create(
            result.input(), formation(result.input().caseId()), result.terminalStatus(), result.detailCode(),
            result.work(), List.of(), result.verifierOutcome()));
    }

    @Test
    void nativeObservedEntryRetainsTheActualPipelinePrefixInTheNewResult() {
        var input = input("ON_DEMAND_VERIFIED_FACTORIZATION", "four-identical-occurrences", "CP03_1_OF_3");
        var execution = PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.executeObservedCase(
            input, formation(input.caseId()));
        var result = execution.measured().result();
        assertEquals("regelsuche.polynomial-theory-utility-candidate-result/v3", result.schema());
        assertEquals(execution.rawWork(), result.observations().rawWork().totalMechanicalWork());
        assertEquals(execution.projection().work(), result.work());
        assertEquals(execution.occurrences().size(), result.observations().occurrences().size());
        assertTrue(result.work().mechanicalWork() > 0);
        assertTrue(result.observations().occurrences().stream()
            .anyMatch(value -> value.terminalStatus() == TerminalStatus.BUDGET_INCONCLUSIVE));
    }

    static ReplayFixture interruptedReplay() {
        var parser = new ExpressionParser();
        var seed = parser.parseExactTerm("(x^2-1)");
        var authorized = new ExactNestedFactorizationTransformationPipeline().transform(seed,
            new TreePosition(List.of(), ExpressionFormatter.format(seed.expression())),
            NativeUnivariateFactorizationEngine.rationals(NativeUnivariateFactorizationPolicy.boundedDefaults()), 0);
        assertTrue(authorized.transformed());
        var store = new VerifiedPolynomialTransitionCacheStore();
        var primitive = authorized.transformation().orElseThrow();
        var retained = store.retain(primitive, "component-test", PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            new VerifiedPolynomialTransitionCacheStore.Observation(primitive.certificateHash(),
                List.of("component-fixture"), List.of()));
        var lookup = store.lookup(retained.lookupRequest());
        var released = store.replay(lookup);
        assertTrue(released.replayed());
        var formation = formation("four-identical-occurrences");
        var input = input("VERIFIED_DERIVED_MACRO_CACHE", formation.caseId(), "CP03_1_OF_3");
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        authority.consume(prefixed("cache.lookup.", lookup.lookupWork()));
        authority.consume(prefixed("cache.replay.", released.replayWork()));
        var parsed = parser.parseExactTerm(formation.sourceExpression());
        var selector = new TreePosition(List.of(0, 0), "pending");
        var position = new TreePosition(selector.path(),
            ExpressionFormatter.format(selector.subtreeAt(parsed.expression()).orElseThrow()));
        var replay = new ExactNestedFactorizationTransformationPipeline(authority)
            .replay(parsed, position, released.authorization().orElseThrow());
        assertEquals(ExactNestedFactorizationTransformationPipeline.Status.BUDGET_INCONCLUSIVE, replay.status());
        assertTrue(authority.work().cacheReplayWork() > 0);
        var hit = PolynomialTheoryUtilityCacheEvent.create(0, input.inputId(), "NONE", Kind.LOOKUP_HIT,
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION, retained.entryId(), lookup.certificateHash());
        var attempt = PolynomialTheoryUtilityCacheEvent.createReplayAttempt(1, input.inputId(), "NONE",
            PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION, retained.entryId(),
            hash(released.certificateHash() + replay.certificateHash()), TerminalStatus.BUDGET_INCONCLUSIVE);
        var occurrences = new ArrayList<Occurrence>();
        for (var path : List.of(List.of(0, 0), List.of(0, 1), List.of(1, 0), List.of(1, 1))) {
            boolean first = occurrences.isEmpty();
            occurrences.add(new Occurrence(occurrences.size(), path, TerminalStatus.BUDGET_INCONCLUSIVE,
                first ? replay.detailCode() : "NOT_INVOKED_AFTER_BUDGET_EXHAUSTION",
                first ? replay.certificateHash() : "NONE", "NONE", 0,
                first ? authority.ledger() : PolynomialWorkLedger.empty(), List.of(),
                first ? List.of(hit.eventId(), attempt.eventId()) : List.of()));
        }
        var observations = PolynomialTheoryUtilityExecutionObservations.create(PolynomialWorkLedger.empty(), occurrences);
        var result = PolynomialTheoryUtilityCandidateResult.createObserved(input, formation,
            "INTERRUPTED_REPLAY_COMPONENT", List.of(), "NOT_REQUESTED", observations);
        return new ReplayFixture(PolynomialTheoryUtilityMeasuredCandidate.create(result, List.of(), List.of(),
            List.of(hit, attempt)), authority.ledger(), authority.work());
    }

    /** Component executions use the frozen row's real authority; seed derivation is separate. */
    static PolynomialTheoryUtilityMeasuredCandidate mixedReplay() {
        var parser = new ExpressionParser();
        var seed = parser.parseExactTerm("(x^2-1)");
        var authorized = new ExactNestedFactorizationTransformationPipeline().transform(seed,
            new TreePosition(List.of(), ExpressionFormatter.format(seed.expression())),
            NativeUnivariateFactorizationEngine.rationals(NativeUnivariateFactorizationPolicy.boundedDefaults()), 0);
        assertTrue(authorized.transformed());
        var store = new VerifiedPolynomialTransitionCacheStore();
        var primitive = authorized.transformation().orElseThrow();
        var retained = store.retain(primitive, "component-test", PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION,
            new VerifiedPolynomialTransitionCacheStore.Observation(primitive.certificateHash(),
                List.of("component-fixture"), List.of()));
        var lookup = store.lookup(retained.lookupRequest());
        var released = store.replay(lookup);
        assertTrue(released.replayed());
        var formation = formation("four-identical-occurrences");
        var input = input("VERIFIED_DERIVED_MACRO_CACHE", formation.caseId(), "CP06_FULL");
        var authority = new PolynomialTheoryUtilityWorkAuthority(input);
        var parsed = parser.parseExactTerm(formation.sourceExpression());
        var occurrences = new ArrayList<Occurrence>();
        var transitions = new ArrayList<PolynomialTheoryUtilityTransitionOutcome>();
        var traces = new ArrayList<PolynomialTheoryUtilityTransitionTrace>();
        var events = new ArrayList<PolynomialTheoryUtilityCacheEvent>();
        for (var path : PolynomialTheoryUtilityExecutionObservations.paths(formation)) {
            var before = authority.work();
            var beforeRaw = authority.ledger();
            var status = TerminalStatus.BUDGET_INCONCLUSIVE;
            String detail = "NOT_INVOKED_AFTER_BUDGET_EXHAUSTION";
            ExactNestedFactorizationTransformationPipeline.Result replay = null;
            boolean lookedUp = false;
            boolean replayed = false;
            try {
                authority.consume(prefixed("cache.lookup.", lookup.lookupWork()));
                lookedUp = true;
                authority.consume(prefixed("cache.replay.", released.replayWork()));
                replayed = true;
                var selector = new TreePosition(path, "pending");
                var position = new TreePosition(path,
                    ExpressionFormatter.format(selector.subtreeAt(parsed.expression()).orElseThrow()));
                replay = new ExactNestedFactorizationTransformationPipeline(authority)
                    .replay(parsed, position, released.authorization().orElseThrow());
                detail = replay.detailCode();
                if (replay.transformed()) {
                    authority.consumePrimitive(VerifiedPolynomialTransitionCacheStore.VerifiedTransition
                        .from(primitive).primitiveExpansion().size());
                    status = TerminalStatus.VALIDATED_TRANSITION;
                } else {
                    assertEquals(ExactNestedFactorizationTransformationPipeline.Status.BUDGET_INCONCLUSIVE,
                        replay.status(), replay.detailCode());
                }
            } catch (PolynomialWorkAuthority.LimitReached exhausted) {
                detail = exhausted.getMessage();
            }
            String transitionId = "NONE";
            if (status == TerminalStatus.VALIDATED_TRANSITION) {
                var range = replay.projection().orElseThrow().selectedRange().orElseThrow();
                String replacement = primitive.transformedExpression().orElseThrow();
                String transformedRoot = formation.sourceExpression().substring(0, range.startInclusive())
                    + "(" + replacement + ")" + formation.sourceExpression().substring(range.endExclusive());
                var transition = PolynomialTheoryUtilityTransitionOutcome.create(transitions.size(), input.inputId(),
                    path, primitive.occurrence().sourceText(), replacement, formation.sourceExpression(), transformedRoot,
                    PolynomialTheoryUtilityExecutionPlan.TRANSFORMATION_ID,
                    PolynomialTheoryUtilityExecutionInputs.profile(input.profileId()).engineId(),
                    primitive.occurrence().sourceEvidenceHash(), replay.certificateHash(),
                    PolynomialTheoryUtilityTransitionOutcome.CacheDisposition.CACHE_HIT_REPLAYED,
                    PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION, retained.entryId(), "NONE",
                    PolynomialTheoryUtilityExecutionObservations.difference(authority.work(), before));
                transitionId = transition.transitionId();
                transitions.add(transition);
                traces.add(PolynomialTheoryUtilityOnDemandVerifiedFactorizationAdapter.trace(transition, replay));
            }
            var ownEvents = new ArrayList<String>();
            if (lookedUp) {
                var event = PolynomialTheoryUtilityCacheEvent.create(events.size(), input.inputId(), transitionId,
                    Kind.LOOKUP_HIT, PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION, retained.entryId(),
                    lookup.certificateHash());
                events.add(event);
                ownEvents.add(event.eventId());
            }
            if (replayed) {
                var event = PolynomialTheoryUtilityCacheEvent.createReplayAttempt(events.size(), input.inputId(),
                    transitionId, PolynomialTheoryUtilityExecutionPlan.CACHE_REVISION, retained.entryId(),
                    hash(released.certificateHash() + (replay == null ? detail : replay.certificateHash())), status);
                events.add(event);
                ownEvents.add(event.eventId());
            }
            occurrences.add(new Occurrence(occurrences.size(), path, status, detail,
                replay == null ? "NONE" : replay.certificateHash(), transitionId,
                authority.work().primitiveWork() - before.primitiveWork(),
                PolynomialTheoryUtilityExecutionObservations.difference(authority.ledger(), beforeRaw), List.of(), ownEvents));
        }
        var observations = PolynomialTheoryUtilityExecutionObservations.create(PolynomialWorkLedger.empty(), occurrences);
        var result = PolynomialTheoryUtilityCandidateResult.createObserved(input, formation,
            "MIXED_REPLAY_COMPONENT", transitions, "RETAINED_OCCURRENCE_OUTCOMES", observations);
        return PolynomialTheoryUtilityMeasuredCandidate.create(result, traces, List.of(), events);
    }

    static PolynomialTheoryUtilityExecutionInput input(String profile, String caseId, String checkpoint) {
        return PolynomialTheoryUtilityExecutionInputs.freeze().inputs().stream()
            .filter(value -> value.profileId().equals(profile) && value.caseId().equals(caseId)
                && value.checkpointId().equals(checkpoint)).findFirst().orElseThrow();
    }

    static PolynomialTheoryUtilityCaseCorpus.FormationCase formation(String caseId) {
        return PolynomialTheoryUtilityCaseCorpus.load().cases().stream()
            .filter(value -> value.caseId().equals(caseId)).findFirst().orElseThrow();
    }

    static String hash(String text) {
        return PolynomialTheoryUtilityExecutionIdentity.sha256(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static PolynomialWorkLedger prefixed(String prefix, PolynomialWorkLedger work) {
        var stages = new LinkedHashMap<String, Long>();
        work.stages().forEach((stage, units) -> stages.put(prefix + stage, units));
        return new PolynomialWorkLedger(stages);
    }

    record ReplayFixture(PolynomialTheoryUtilityMeasuredCandidate measured,
            PolynomialWorkLedger rawWork, PolynomialTheoryUtilityWorkBreakdown work) { }
}
