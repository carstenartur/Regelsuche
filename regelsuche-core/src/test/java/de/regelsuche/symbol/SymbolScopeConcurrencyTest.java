package de.regelsuche.symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class SymbolScopeConcurrencyTest {
    @Test
    void concurrentResolutionReturnsOneSymbolWithoutConsumingExtraOrdinals() throws Exception {
        var scope = new SymbolScope(new UUID(0, 1));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            List<Future<SymbolId>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return scope.resolve("y");
                }));
            }
            start.countDown();
            var symbol = results.getFirst().get(10, TimeUnit.SECONDS);
            for (var result : results) assertSame(symbol, result.get(10, TimeUnit.SECONDS));
            assertEquals(1, scope.snapshot().bindings().size());
            assertEquals(2, scope.snapshot().nextOrdinal());
        }
    }

    @Test
    void racingBatchesCannotPartiallyAllocateOrExceedCapacity() throws Exception {
        var scope = new SymbolScope(new UUID(0, 2), new SymbolScope.Limits(2, 1, 0));
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (var names : List.of(List.of("a", "b"), List.of("c", "d"))) {
                results.add(workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        scope.resolveAll(names);
                        return true;
                    } catch (IllegalArgumentException expectedCapacityFailure) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successful = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) successful++;
            assertEquals(1, successful);
            var snapshot = scope.snapshot();
            assertTrue(Set.of(Set.of("a", "b"), Set.of("c", "d")).contains(snapshot.bindings().keySet()));
            assertEquals(3, snapshot.nextOrdinal());
            assertEquals(snapshot, SymbolScope.restore(snapshot).snapshot());
        }
    }
}
