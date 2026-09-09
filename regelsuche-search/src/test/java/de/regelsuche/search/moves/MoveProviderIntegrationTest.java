package de.regelsuche.search.moves;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.transform.HypothesisTransformationEngine;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.Transformation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MoveProviderIntegrationTest {
    private static Transformation step(String rule, String output) {
        return new Transformation(rule, output, RewriteKind.NORMALIZE, false, 0, true, rule + ":" + output);
    }
    private static MoveProvider.Descriptor descriptor(String id, SearchMove.SourceKind kind, List<String> assumptions) {
        return new MoveProvider.Descriptor(id, "test-family", kind, SearchMove.ProofStrength.REPLAYABLE,
            assumptions, SearchMove.ValueEvidence.UNKNOWN, "fixture-proof");
    }

    @Test
    void onePickerOrdersPrimitiveAndLearnedMovesWithoutFlatteningTheirProofsOrDroppingBatchWork() {
        var macro = new RewriteCandidate("thought", "a", "c", List.of(step("one", "b"), step("two", "c"))).toTransformation();
        var primitive = new EngineMoveProvider(descriptor("primitive", SearchMove.SourceKind.PRIMITIVE, List.of()),
            expression -> List.of(step("one", "b")), true);
        var learned = new EngineMoveProvider(descriptor("frozen-rule", SearchMove.SourceKind.LEARNED, List.of()),
            expression -> List.of(macro), true);
        var scores = new AtomicInteger();
        MovePriorityPolicy policy = (move, state, context) -> {
            scores.incrementAndGet();
            return move.sourceKind() == SearchMove.SourceKind.LEARNED ? 10 : 0;
        };
        var engine = new ProviderTransformationEngine(List.of(primitive, learned), policy, MoveContext.frozen("c"));
        var picker = engine.picker(MoveState.root("a"));
        var first = picker.next().orElseThrow();
        assertEquals("frozen-rule", first.ruleId());
        assertEquals(macro.provenance(), first.provenance());
        assertEquals(List.of("one", "two"), first.primitiveExpansion().stream().map(Transformation::rule).toList());
        assertEquals(2, first.applicationCost());
        assertEquals(-1, first.verificationCost(), "unmeasured verification must not be displayed as free");
        assertEquals(SearchMove.ProofStrength.REPLAYABLE, first.proofStrength());
        assertEquals(2, scores.get());
        assertEquals("one", picker.next().orElseThrow().ruleId());
        assertTrue(picker.next().isEmpty());
        assertTrue(picker.complete());
        assertEquals(2, picker.workMetrics().sourceInvocations());
        assertEquals(2, picker.workMetrics().priorityCandidatesOrdered());
        assertEquals(macro, engine.transformMeasured("a").transformations().getFirst());
    }

    @Test
    void missingAssumptionsRejectBeforeInvokingTheProviderAndDoNotAlterItsTrust() {
        var calls = new AtomicInteger();
        var provider = new EngineMoveProvider(descriptor("conditional", SearchMove.SourceKind.LEARNED, List.of("a != 0")),
            expression -> { calls.incrementAndGet(); return List.of(step("conditional", "b")); }, false);
        var empty = provider.candidates(MoveState.root("a"), MoveContext.frozen("b"));
        assertTrue(empty.moves().isEmpty());
        assertEquals(0, calls.get());
        assertEquals(1, empty.work().requirementRejections());
        var admitted = provider.candidates(MoveState.root("a"), new MoveContext("b", List.of("a != 0"), MoveContext.Phase.TRAIN));
        assertEquals(1, calls.get());
        assertEquals(SearchMove.ProofStrength.REPLAYABLE, admitted.moves().getFirst().proofStrength());
        assertFalse(admitted.complete(), "an opaque source limit is not proof of complete enumeration");
    }

    @Test
    void hypothesisWrapperExposesIndependentProvidersInsteadOfApplyingItsOldGlobalCandidateCap() {
        var wrapper = new HypothesisTransformationEngine(expression -> List.of(step("base", "b")),
            List.of(expression -> List.of(step("h1", "c")), expression -> List.of(step("h2", "d"))), 1);
        assertEquals(2, wrapper.transform("a").size(), "legacy baseline remains available");
        var providers = MoveProviders.from(wrapper);
        assertEquals(3, providers.size());
        var engine = new ProviderTransformationEngine(providers, MovePriorityPolicy.INVENTORY_ORDER, MoveContext.frozen("d"));
        assertEquals(3, engine.transform("a").size());
        assertEquals(providers, MoveProviders.from(engine));
        assertEquals(SearchMove.ProofStrength.UNVALIDATED, providers.getLast().descriptor().proofStrength());
        assertThrows(IllegalArgumentException.class, () -> new ProviderTransformationEngine(providers,
            MovePriorityPolicy.INVENTORY_ORDER, new MoveContext("d", List.of(), MoveContext.Phase.PRODUCTION)));
        assertThrows(IllegalArgumentException.class, () -> new EagerMovePicker(providers,
            (move, state, context) -> Double.NaN, MoveState.root("a"), MoveContext.frozen("d")));
    }
}
