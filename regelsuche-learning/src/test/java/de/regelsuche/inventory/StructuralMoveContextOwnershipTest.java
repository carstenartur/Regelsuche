package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.retention.RetainedGraph;
import de.regelsuche.retention.RetainedOperation;
import java.util.*;
import org.junit.jupiter.api.Test;

class StructuralMoveContextOwnershipTest {
    private static final String KEY = "{\"root\":\"ADD\",\"degree\":2,\"variables\":3,\"products\":4,\"powers\":5,\"repeated\":6,\"assumptions\":[\"real(x)\"],\"capabilities\":[\"proof\"]}";
    private static final String TYPED = "regelsuche.typed-structural-context/v1:" + KEY;
    private static StructuralMoveContext context() {
        return new StructuralMoveContext("ADD", 2, 3, 4, 5, 6, List.of("real(x)"), List.of("proof"), 7);
    }

    private static final class Meter implements RetainedOperation.Sink {
        RetainedOperation scope;
        final Map<Long, Integer> charges = new HashMap<>();
        final List<Set<String>> snapshots = new ArrayList<>();
        long failFromUnits = Long.MAX_VALUE;
        final ArithmeticException failure = new ArithmeticException("typed context copy debit");
        @Override public void executionWork(long units) {
            charges.merge(units, 1, Integer::sum);
            if (units >= failFromUnits) { failFromUnits = Long.MAX_VALUE; throw failure; }
        }
        @Override public void validationWork(long units) { executionWork(units); }
        @Override public void retainedReferences(RetainedGraph.Visitor visitor) { }
        @Override public void checkpoint() {
            RetainedGraph.measure(scope);
            var pending = new ArrayDeque<Object>();
            var seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            var strings = new HashSet<String>();
            var visitor = new RetainedGraph.Visitor() {
                @Override public void reference(Object value) { if (value != null) pending.add(value); }
                @Override public void requireExact(Object value, Class<?> type) { assertEquals(type, value.getClass()); }
            };
            visitor.reference(scope);
            while (!pending.isEmpty()) {
                Object value = pending.removeFirst();
                if (!seen.add(value)) continue;
                if (value instanceof String text) strings.add(text);
                if (value instanceof RetainedGraph.View view) view.retainedReferences(visitor);
                else if (value instanceof Object[] values) for (Object item : values) visitor.reference(item);
                else if (value instanceof Collection<?> values) values.forEach(visitor::reference);
            }
            snapshots.add(strings);
        }
    }

    @Test void keyKeepsHistoricalBytesAndPaysItsWriterCopyOnlyOnce() {
        assertEquals(KEY, context().key());
        assertEquals(TYPED, context().typedKey());
        var meter = new Meter();
        try (var scope = RetainedOperation.open(meter)) {
            meter.scope = scope;
            assertEquals(KEY, context().key());
            assertEquals(1, meter.charges.getOrDefault(KEY.length() + 1L, 0), "JsonWriter owns and pays its completed output copy");
            assertEquals(0, meter.charges.getOrDefault((long) KEY.length(), 0), "the context must not charge the same copied characters again");
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
        }
    }

    @Test void failedTypedPrefixDebitStillOwnsBothCompletedStrings() {
        var meter = new Meter(); meter.failFromUnits = TYPED.length();
        try (var scope = RetainedOperation.open(meter)) {
            meter.scope = scope;
            assertSame(meter.failure, assertThrows(ArithmeticException.class, () -> context().typedKey()));
            assertTrue(meter.snapshots.stream().anyMatch(strings -> strings.contains(KEY) && strings.contains(TYPED)),
                "the plain key and actual completed prefixed copy overlap before its debit can abort");
            assertEquals(0, RetainedGraph.measure(scope).retained().characters());
            assertEquals(TYPED, context().typedKey());
        }
        assertEquals(0, RetainedGraph.measure(meter.scope).retained().characters());
    }
}
