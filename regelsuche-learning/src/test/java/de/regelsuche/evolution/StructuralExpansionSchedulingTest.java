package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.search.moves.IncrementalMovePicker;
import de.regelsuche.search.moves.IncrementalProviderContract;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MovePriorityPolicy;
import de.regelsuche.search.moves.MoveProvider;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.RegisteredIncrementalMoveProvider;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.search.moves.StagedMovePicker;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StructuralExpansionSchedulingTest {
    @Test
    void learnedBurstDoesNotForceDeferredExpansionAheadOfPreparation() {
        var expansion = expansionDescriptor();
        assertEquals(SearchMove.SourceKind.BRIDGE, expansion.sourceKind());
        for (boolean incremental : List.of(false, true)) {
            var preparationCalls = new AtomicInteger();
            var expansionCalls = new AtomicInteger();
            var learned = descriptor("learned-fixture", SearchMove.SourceKind.LEARNED);
            var preparation = descriptor("preparation-fixture", SearchMove.SourceKind.PREPARATION);
            var policy = new MovePriorityPolicy() {
                @Override public double score(SearchMove move, MoveState state, MoveContext context) { return 0; }
                @Override public Stage stage(MoveProvider.Descriptor provider, MoveState state, MoveContext context) {
                    return provider.sourceKind() == SearchMove.SourceKind.LEARNED ? Stage.VALUABLE_LEARNED : Stage.EXPENSIVE;
                }
            };
            var providers = incremental
                ? List.of(lazy(learned, 2, new AtomicInteger()), lazy(preparation, 1, preparationCalls), lazy(expansion, 1, expansionCalls))
                : List.of(batch(learned, 2, new AtomicInteger()), batch(preparation, 1, preparationCalls), batch(expansion, 1, expansionCalls));
            var state = MoveState.root("x");
            var context = MoveContext.frozen("");
            if (incremental) {
                try (var picker = new IncrementalMovePicker(providers, policy, state, context)) {
                    assertEquals("learned-fixture", picker.next().orElseThrow().ruleId());
                    assertEquals("learned-fixture", picker.next().orElseThrow().ruleId());
                    assertEquals("preparation-fixture", picker.next().orElseThrow().ruleId());
                }
            } else {
                var picker = new StagedMovePicker(providers, policy, state, context);
                assertEquals("learned-fixture", picker.next().orElseThrow().ruleId());
                assertEquals("learned-fixture", picker.next().orElseThrow().ruleId());
                assertEquals("preparation-fixture", picker.next().orElseThrow().ruleId());
            }
            assertTrue(preparationCalls.get() > 0);
            assertEquals(0, expansionCalls.get(), "deferred expansion must not be forced by primitive fairness");
        }
    }

    private static MoveProvider.Descriptor expansionDescriptor() {
        var inventory = TypedLearnedMoveInventory.primitives(TraceStrategyTransferExample.inventory());
        return StructuralExpansionRules.attach(inventory.newSearchSession(false, 0, 0)).providers().stream()
            .map(MoveProvider::descriptor).filter(StructuralExpansionRules::isExpansionProvider).findFirst().orElseThrow();
    }

    private static MoveProvider.Descriptor descriptor(String id, SearchMove.SourceKind kind) {
        return new MoveProvider.Descriptor(id, id, kind, SearchMove.ProofStrength.REPLAYABLE,
            List.of(), SearchMove.ValueEvidence.UNKNOWN, "scheduling-fixture");
    }

    private static MoveProvider batch(MoveProvider.Descriptor descriptor, int count, AtomicInteger calls) {
        return new MoveProvider() {
            @Override public Descriptor descriptor() { return descriptor; }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                calls.incrementAndGet();
                var moves = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> SearchMove.from(new Transformation(descriptor.id(), "v" + i), descriptor, 1)).toList();
                return new Batch(moves, TransformationWorkMetrics.flatEngine(moves.size()), true);
            }
        };
    }

    private static MoveProvider lazy(MoveProvider.Descriptor descriptor, int count, AtomicInteger calls) {
        var definition = new IncrementalProviderContract.Definition(IncrementalProviderContract.REVISION, descriptor.id(),
            IncrementalProviderContract.Kind.REGISTERED_SCHEMA, "scheduling-fixture", "v1",
            IncrementalProviderContract.Transport.TYPED_AST_JSON, IncrementalProviderContract.Mathematics.PRIMITIVE, null);
        var registration = new IncrementalProviderContract.Registration(definition, (state, context, meter) -> {
            calls.incrementAndGet();
            return new IncrementalProviderContract.Source() {
                private int index;
                @Override public Optional<Transformation> next(long allowance) {
                    if (index >= count) return Optional.empty();
                    var transformation = new Transformation(descriptor.id(), "v" + index++);
                    meter.charge(transformation.executionWork());
                    return Optional.of(transformation);
                }
                @Override public IncrementalProviderContract.Status status() {
                    return index < count ? IncrementalProviderContract.Status.READY : IncrementalProviderContract.Status.EXHAUSTED;
                }
            };
        });
        return new RegisteredIncrementalMoveProvider(descriptor, definition,
            new IncrementalProviderContract.Registry(List.of(registration)));
    }
}
