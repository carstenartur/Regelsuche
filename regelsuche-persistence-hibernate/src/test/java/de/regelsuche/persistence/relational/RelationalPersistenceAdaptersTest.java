package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RelationalPersistenceAdaptersTest {

    @Test
    void exposesAllAdaptersAndClosesOwnedFactoryExactlyOnce() {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicInteger closeCalls = new AtomicInteger();
        EntityManagerFactory factory = (EntityManagerFactory) Proxy.newProxyInstance(
            EntityManagerFactory.class.getClassLoader(),
            new Class<?>[] {EntityManagerFactory.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isOpen" -> open.get();
                case "close" -> {
                    closeCalls.incrementAndGet();
                    open.set(false);
                    yield null;
                }
                case "toString" -> "FakeEntityManagerFactory";
                default -> defaultValue(method.getReturnType());
            });

        RelationalPersistenceAdapters adapters = RelationalPersistenceAdapters.of(factory);

        assertTrue(adapters.hypotheses().isPresent());
        assertNotNull(adapters.experiments());
        assertNotNull(adapters.searchRuns());
        assertNotNull(adapters.proofJobs());
        assertNotNull(adapters.reports());
        assertNotNull(adapters.seeds());
        assertNotNull(adapters.benchmarks());
        assertNotNull(adapters.counterexamples());
        assertNotNull(adapters.searchIndex());

        adapters.close();
        assertFalse(open.get());
        assertEquals(1, closeCalls.get());

        adapters.close();
        assertEquals(1, closeCalls.get());
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
