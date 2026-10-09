package de.regelsuche.transform;

import static de.regelsuche.ast.BinaryOperator.POW;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.ast.*;
import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import de.regelsuche.symbol.SymbolId;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExactMonomialScopedSymbolOwnershipTest {
    private static final UUID NAMESPACE = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    private static final SymbolId SYMBOL = new SymbolId(NAMESPACE, 19);
    private static final String HEX = "0123456789abcdef0123456789abcdef";
    private static final String CANONICAL = NAMESPACE + ":19";
    private enum Stage { HEX, UUID_TEXT, CANONICAL_TEXT, UUID_VALUE, SYMBOL }
    private static final class DebitAbort extends RuntimeException { }
    private static final class CleanupAbort extends RuntimeException { }

    @Test void scopedSquareInferenceOwnsConversionStringsAndIdentityBeforeVariableHandoff() {
        var observation = new Observation();
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var result = infer(observation.input);
            assertTrue(result.matched());
            assertEquals(new BinaryExpr(observation.variable, POW, new NumberExpr(2)), result.bindings().get("A"));
            assertEquals(0, result.visitedBranches());
        }
        assertTrue(observation.seenStages.containsAll(EnumSet.allOf(Stage.class)),
            "hex, UUID text/value, canonical text and the parsed symbol must exist before the new variable owns them");
        assertTrue(observation.seenText.containsAll(List.of("01234567", "89ab", "cdef", "0123", "456789abcdef", "19")),
            "the actual substring inputs must remain visible through conversion");
        assertTrue(observation.work > 0);
        assertReleased(observation);
    }

    @ParameterizedTest
    @CsvSource({"HEX,false", "HEX,true", "UUID_TEXT,false", "UUID_TEXT,true",
        "CANONICAL_TEXT,false", "CANONICAL_TEXT,true", "UUID_VALUE,false", "UUID_VALUE,true",
        "SYMBOL,false", "SYMBOL,true"})
    void conversionDebitFailurePreservesPrimaryReleasesOwnersAndAllowsRetry(Stage stop, boolean sameCleanup) {
        var observation = new Observation(); observation.stop = stop; observation.sameCleanup = sameCleanup;
        try (var scope = RetainedOperation.open(observation)) {
            observation.scope = scope;
            var failure = assertThrows(DebitAbort.class, () -> infer(observation.input));
            assertSame(observation.failure, failure);
            assertTrue(observation.failedStageSeen, "failed debit still observes its completed conversion product");
            if (sameCleanup) assertEquals(0, failure.getSuppressed().length);
            else assertTrue(Arrays.asList(failure.getSuppressed()).contains(observation.cleanup));
            observation.cleanupEnabled = false;
            assertEquals(0, RetainedGraph.measure(scope).retained().nodes());
            assertTrue(infer(observation.input).matched());
        }
        assertReleased(observation);
    }

    @Test void observedConversionKeepsTheExistingValueAndRejectionContracts() {
        for (long ordinal : List.of(1L, 19L, Long.MAX_VALUE)) {
            var expected = new SymbolId(NAMESPACE, ordinal);
            String identifier = expected.identifier(), canonical = expected.canonicalText();
            var observation = new Observation();
            try (var scope = RetainedOperation.open(observation)) {
                observation.scope = scope;
                assertEquals(expected, SymbolId.fromIdentifier(identifier));
                assertEquals(expected, SymbolId.fromCanonicalText(canonical));
            }
            assertReleased(observation);
        }
        for (boolean identifier : List.of(false, true)) {
            var invalid = identifier
                ? Arrays.asList(null, "rsym_", "rsym_" + HEX + "_01", "rsym_" + HEX + "_9223372036854775808")
                : Arrays.asList(null, "0-0-0-0-0:1", NAMESPACE + ":01", NAMESPACE + ":0", NAMESPACE + ":-1",
                    NAMESPACE + ":9223372036854775808", CANONICAL.toUpperCase(Locale.ROOT));
            for (String value : invalid) {
                var expected = assertThrows(RuntimeException.class, () -> convert(value, identifier));
                var observation = new Observation();
                try (var scope = RetainedOperation.open(observation)) {
                    observation.scope = scope;
                    var actual = assertThrows(RuntimeException.class, () -> convert(value, identifier));
                    assertEquals(expected.getClass(), actual.getClass());
                    assertEquals(expected.getMessage(), actual.getMessage());
                    assertEquals(0, RetainedGraph.measure(scope).retained().nodes());
                }
                assertReleased(observation);
            }
        }
    }

    private static SymbolId convert(String value, boolean identifier) {
        return identifier ? SymbolId.fromIdentifier(value) : SymbolId.fromCanonicalText(value);
    }

    private static EquivalenceAwarePatternMatcher.MatchAttempt infer(Expr input) {
        return EquivalenceAwarePatternMatcher.matchDetailed(
            PatternExpr.op(POW, PatternExpr.var("A"), PatternExpr.num(2)), input, Map.of(), RecognitionProfile.algebraicAc());
    }

    private static void assertReleased(Observation observation) {
        var usage = RetainedGraph.measure(observation.scope).retained();
        assertEquals(0, usage.nodes()); assertEquals(0, usage.characters());
    }

    private static final class Observation implements RetainedOperation.Sink {
        final VariableExpr variable = VariableExpr.scoped(SYMBOL);
        final Expr input = new BinaryExpr(variable, POW, new NumberExpr(4));
        final Set<Stage> seenStages = EnumSet.noneOf(Stage.class);
        final Set<String> seenText = new HashSet<>();
        final CleanupAbort cleanup = new CleanupAbort();
        RetainedOperation scope;
        Stage stop;
        DebitAbort failure;
        boolean sameCleanup, cleanupEnabled = true, failedStageSeen;
        long work;

        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { visitor.reference(scope); }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void executionWork(long units) {
            work += units;
            if (scope == null) return;
            if (failure != null) {
                if (cleanupEnabled && units == 4) {
                    if (sameCleanup) throw failure;
                    throw cleanup;
                }
            } else if (stop != null && snapshot().contains(stop)) {
                failure = new DebitAbort();
                throw failure;
            }
        }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var stages = snapshot();
            seenStages.addAll(stages);
            failedStageSeen |= failure != null && stages.contains(stop);
        }
        private Set<Stage> snapshot() {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            var pending = new ArrayDeque<Object>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            boolean parsedSymbol = false, renderedVariable = false, parsedNamespace = false;
            Set<Stage> stages = EnumSet.noneOf(Stage.class);
            while (!pending.isEmpty()) {
                Object value = pending.remove();
                if (!seen.add(value)) continue;
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Map<?, ?> map) map.forEach((key, item) -> { visitor.reference(key); visitor.reference(item); });
                else if (value instanceof Collection<?> items) items.forEach(visitor::reference);
                else if (value instanceof Object[] items) for (Object item : items) visitor.reference(item);
                else if (value instanceof Optional<?> optional) optional.ifPresent(visitor::reference);
                else if (value instanceof BinaryExpr binary) { visitor.reference(binary.left()); visitor.reference(binary.right()); }
                else if (value instanceof VariableExpr leaf) {
                    renderedVariable |= leaf != variable;
                    visitor.reference(leaf.name()); visitor.reference(leaf.symbol().orElse(null));
                } else if (value instanceof SymbolId symbol) {
                    parsedSymbol |= symbol != SYMBOL && symbol.equals(SYMBOL);
                    visitor.reference(symbol.namespace());
                } else if (value instanceof UUID namespace) parsedNamespace |= namespace != NAMESPACE && namespace.equals(NAMESPACE);
                else if (value instanceof String text) {
                    seenText.add(text);
                    if (text.equals(HEX)) stages.add(Stage.HEX);
                    if (text.equals(NAMESPACE.toString())) stages.add(Stage.UUID_TEXT);
                    if (text.equals(CANONICAL)) stages.add(Stage.CANONICAL_TEXT);
                }
            }
            if (parsedNamespace && !renderedVariable) stages.add(Stage.UUID_VALUE);
            if (parsedSymbol && !renderedVariable) stages.add(Stage.SYMBOL);
            return stages;
        }
    }
}
