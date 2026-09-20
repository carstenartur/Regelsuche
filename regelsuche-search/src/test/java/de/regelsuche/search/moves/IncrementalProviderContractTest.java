package de.regelsuche.search.moves;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.transform.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IncrementalProviderContractTest {
    static final MoveContext CONTEXT = MoveContext.frozen("a");
    static final PatternRewriteRule RULE = (PatternRewriteRule) AstRewriteTransformationEngine.defaultRules().stream()
        .filter(rule -> rule.id().equals("ast_add_zero_right")).findFirst().orElseThrow();

    @Test void nativeAndRegisteredProvidersShareTheGeneralSessionContract() {
        IncrementalMoveProvider provider = nativeProvider();
        assertEquals(Kind.NATIVE_RULES, provider.contractDefinition().kind());
        assertEquals(Transport.PARSER_TEXT, provider.contractDefinition().transport());
        assertNotNull(provider.contractDefinition().nativeDefinition());
        try (var cursor = provider.openSession(MoveState.root("a + 0"), CONTEXT)) {
            assertTrue(cursor.next(1).isEmpty());
            assertTrue(cursor.next(1000).isPresent());
            assertEquals(1, cursor.snapshot().work().mathematics().primitiveRewrites());
        }
    }

    @Test void explicitRegistrationBindsModelSemanticsAndTransport() {
        var definition = definition("model-1", Transport.PARSER_TEXT);
        var registry = new Registry(List.of(new Registration(definition, nativeFactory())));
        var provider = new RegisteredIncrementalMoveProvider(descriptor(), definition, registry);
        assertEquals(definition, provider.contractDefinition());
        assertThrows(IllegalArgumentException.class, () -> new RegisteredIncrementalMoveProvider(descriptor(),
            definition("model-2", Transport.PARSER_TEXT), registry));
        assertThrows(IllegalArgumentException.class, () -> new RegisteredIncrementalMoveProvider(descriptor(),
            definition("model-1", Transport.TYPED_AST_JSON), registry));
        assertThrows(IllegalArgumentException.class, () -> new RegisteredIncrementalMoveProvider(descriptor(), definition, new Registry(List.of())));
    }

    @Test void zeroAllowanceDoesNotOpenAndLaterPullResumesWithoutRestarting() {
        var provider = registered(nativeFactory());
        try (var cursor = provider.openSession(MoveState.root("(a + 0) + 0"), CONTEXT)) {
            assertEquals(Status.OPEN, cursor.snapshot().status());
            assertTrue(cursor.next(0).isEmpty());
            assertEquals(0, cursor.snapshot().work().units(Operation.OPEN));
            assertTrue(cursor.next(1).isEmpty());
            assertEquals(Status.LIMIT, cursor.snapshot().status());
            assertTrue(cursor.snapshot().resumable());
            var first = cursor.next(1000).orElseThrow();
            assertEquals("a + 0", first.transformedExpression());
            assertEquals(1, cursor.snapshot().emittedCandidates());
            while (cursor.next(1000).isPresent()) { /* finish the same traversal */ }
            assertEquals(Status.EXHAUSTED, cursor.snapshot().status());
            assertEquals(1, cursor.snapshot().work().units(Operation.OPEN));
            assertEquals(1, cursor.snapshot().work().units(Operation.LOAD));
        }
    }

    @Test void earlyCloseIsIdempotentAndRetainsMathematicalWork() {
        var cursor = registered(nativeFactory()).openSession(MoveState.root("(a + 0) + 0"), CONTEXT);
        assertTrue(cursor.next(1000).isPresent());
        long paid = cursor.snapshot().work().metrics().totalWorkUnitsV2();
        cursor.close();
        var closed = cursor.snapshot();
        assertEquals(Status.CLOSED, closed.status());
        assertTrue(closed.closed());
        assertEquals(1, closed.work().mathematics().primitiveRewrites());
        assertTrue(closed.work().metrics().totalWorkUnitsV2() > paid);
        cursor.close();
        assertEquals(closed, cursor.snapshot());
        assertTrue(cursor.next(1000).isEmpty());
    }

    @Test void failedOpenKeepsPaidWorkAndAbortAndClose() {
        var cursor = registered((state, context, meter) -> {
            meter.charge(Operation.LOAD, 7);
            meter.charge(new ExecutionWork(0, 1, 11));
            throw new IllegalStateException("failed after exact work");
        }).openSession(MoveState.root("a + 0"), CONTEXT);
        assertTrue(cursor.next(1000).isEmpty());
        assertEquals(Status.FAILED, cursor.snapshot().status());
        assertEquals(11, cursor.snapshot().work().mathematics().exactTheoryWorkUnits());
        assertEquals(1, cursor.snapshot().work().units(Operation.ABORT));
        cursor.close();
        assertEquals(Status.FAILED, cursor.snapshot().status());
        assertTrue(cursor.snapshot().closed());
        assertEquals(7, cursor.snapshot().work().units(Operation.LOAD));
        assertEquals(1, cursor.snapshot().work().units(Operation.CLOSE));
    }

    @Test void failedPullRetainsWorkAndIsNeverRestartedByAnotherPull() {
        var cursor = registered((state, context, meter) -> new Source() {
            @Override public Optional<Transformation> next(long allowance) {
                meter.charge(Operation.MATCH, 5);
                meter.charge(new ExecutionWork(0, 1, 9));
                throw new IllegalStateException("failed matching application");
            }
            @Override public Status status() { return Status.READY; }
        }).openSession(MoveState.root("a + 0"), CONTEXT);
        assertTrue(cursor.next(100).isEmpty());
        var failed = cursor.snapshot();
        assertEquals(Status.FAILED, failed.status());
        assertFalse(failed.accountingComplete());
        assertEquals(9, failed.work().mathematics().exactTheoryWorkUnits());
        assertEquals(5, failed.work().units(Operation.MATCH));
        assertTrue(cursor.next(100).isEmpty());
        assertEquals(failed, cursor.snapshot());
        cursor.close();
        assertEquals(Status.FAILED, cursor.snapshot().status());
    }

    @Test void unsupportedRevisionsAndInventedNativeDefinitionsAreRejected() {
        var definition = definition("model-1", Transport.PARSER_TEXT);
        assertThrows(IllegalArgumentException.class, () -> new Definition("future/v9", definition.providerId(), definition.kind(),
            definition.modelRevision(), definition.semanticsRevision(), definition.transport(), definition.mathematics(), null));
        assertThrows(IllegalArgumentException.class, () -> new Definition(REVISION, definition.providerId(), Kind.REGISTERED_SCHEMA,
            definition.modelRevision(), definition.semanticsRevision(), definition.transport(), definition.mathematics(), nativeProvider().definition()));
        var registration = new Registration(definition, nativeFactory());
        assertThrows(IllegalArgumentException.class, () -> new Registry(List.of(registration, registration)));
    }

    @Test void legacyNativeWorkExhaustionIsTerminalButExplicitResumeContinues() {
        var engine = engine();
        try (var legacy = engine.openCursor("a + 0")) {
            assertTrue(legacy.next(0).isEmpty());
            assertTrue(legacy.next(1000).isEmpty());
            assertEquals(TransformationCursor.Status.WORK_EXHAUSTED, legacy.snapshot().status());
        }
        try (var resumed = engine.openResumableCursor("a + 0", 10000)) {
            assertTrue(resumed.next(1).isEmpty());
            assertEquals(TransformationCursor.Status.WORK_EXHAUSTED, resumed.snapshot().status());
            assertTrue(resumed.next(1000).isPresent());
            assertEquals(1, resumed.work().units(TransformationCursor.Operation.PARSE));
            assertEquals(1, resumed.work().units(TransformationCursor.Operation.OPEN));
            assertNotEquals(TransformationCursor.WORK_REVISION, resumed.snapshot().workRevision());
        }
    }

    static RegisteredIncrementalMoveProvider registered(Factory factory) {
        var definition = definition("model-1", Transport.PARSER_TEXT);
        return new RegisteredIncrementalMoveProvider(descriptor(), definition,
            new Registry(List.of(new Registration(definition, factory))));
    }
    static Definition definition(String model, Transport transport) {
        return new Definition(REVISION, "registered-zero", Kind.REGISTERED_SCHEMA, model,
            "scalar-exact/v1", transport, Mathematics.MIXED, null);
    }
    static MoveProvider.Descriptor descriptor() {
        return new MoveProvider.Descriptor("registered-zero", "zero", SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.REPLAYABLE, List.of(),
            new SearchMove.ValueEvidence(1, 0, 1, 3, 1, false, "test-ranking"), "registered-zero/v1");
    }
    static PreparedAstRewriteTransformationEngine engine() {
        return new PreparedAstRewriteTransformationEngine(List.of(RULE), Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
    static NativeIncrementalMoveProvider nativeProvider() {
        return new NativeIncrementalMoveProvider(new MoveProvider.Descriptor("native-zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "native-zero/v1"), engine());
    }
    static Factory nativeFactory() {
        return (state, context, meter) -> {
            meter.charge(Operation.LOAD, 1);
            var nativeCursor = engine().openResumableCursor(state.expression(), 10000);
            return new Source() {
                long mechanics, primitives;
                private void collect() {
                    var work = nativeCursor.work();
                    meter.charge(Operation.MATCH, work.mechanicalUnits() - mechanics);
                    meter.charge(new ExecutionWork(work.primitiveRewrites() - primitives, 0, 0));
                    mechanics = work.mechanicalUnits(); primitives = work.primitiveRewrites();
                }
                @Override public Optional<Transformation> next(long allowance) {
                    try { return nativeCursor.next(allowance); } finally { collect(); }
                }
                @Override public Status status() {
                    return switch (nativeCursor.snapshot().status()) {
                        case READY -> Status.READY;
                        case EXHAUSTED -> nativeCursor.snapshot().complete() ? Status.EXHAUSTED : Status.INCONCLUSIVE;
                        case WORK_EXHAUSTED -> Status.LIMIT;
                        default -> Status.INCONCLUSIVE;
                    };
                }
                @Override public void close() { nativeCursor.close(); collect(); }
            };
        };
    }
}
