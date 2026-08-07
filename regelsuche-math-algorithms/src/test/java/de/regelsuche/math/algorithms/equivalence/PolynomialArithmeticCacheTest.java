package de.regelsuche.math.algorithms.equivalence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PolynomialArithmeticCacheTest {
    @Test
    void repeatedExpressionReusesImmutableParsedPolynomial() {
        PolynomialArithmetic arithmetic = new PolynomialArithmetic(8);

        Polynomial first = arithmetic.parse("x^2 + 2*x + 1").orElseThrow();
        Polynomial second = arithmetic.parse("x^2 + 2*x + 1").orElseThrow();

        assertSame(first, second);
        assertEquals(1, arithmetic.parseCacheSize());
        assertEquals(8, arithmetic.parseCacheCapacity());
    }

    @Test
    void unsupportedExpressionsAreCachedToo() {
        PolynomialArithmetic arithmetic = new PolynomialArithmetic(8);

        assertFalse(arithmetic.parse("sin(x)").isPresent());
        assertEquals(1, arithmetic.parseCacheSize());
        assertFalse(arithmetic.parse("sin(x)").isPresent());
        assertEquals(1, arithmetic.parseCacheSize());
    }

    @Test
    void leastRecentlyUsedEntryIsEvictedAtCapacity() {
        PolynomialArithmetic arithmetic = new PolynomialArithmetic(2);

        Polynomial firstX = arithmetic.parse("x").orElseThrow();
        Polynomial firstY = arithmetic.parse("y").orElseThrow();
        assertSame(firstX, arithmetic.parse("x").orElseThrow());
        arithmetic.parse("z").orElseThrow();

        assertEquals(2, arithmetic.parseCacheSize());
        assertSame(firstX, arithmetic.parse("x").orElseThrow());
        assertNotSame(firstY, arithmetic.parse("y").orElseThrow());
        assertEquals(2, arithmetic.parseCacheSize());
    }

    @Test
    void concurrentMissesConvergeOnSingleCachedPolynomial() throws Exception {
        PolynomialArithmetic arithmetic = new PolynomialArithmetic(8);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Polynomial>> tasks = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                tasks.add(() -> arithmetic.parse("(x + y)^4 - 2*x*y").orElseThrow());
            }
            List<Future<Polynomial>> futures = executor.invokeAll(tasks);
            Polynomial cached = futures.get(0).get();
            for (Future<Polynomial> future : futures) {
                assertSame(cached, future.get());
            }
            assertEquals(1, arithmetic.parseCacheSize());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void zeroCapacityPreservesUncachedBehavior() {
        PolynomialArithmetic arithmetic = new PolynomialArithmetic(0);

        Polynomial first = arithmetic.parse("x + 1").orElseThrow();
        Polynomial second = arithmetic.parse("x + 1").orElseThrow();

        assertEquals(first, second);
        assertNotSame(first, second);
        assertEquals(0, arithmetic.parseCacheSize());
    }

    @Test
    void negativeCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PolynomialArithmetic(-1));
    }
}
