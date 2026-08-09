package de.regelsuche.persistence.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.TypedQuery;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HibernateEntityRepositoryTest {

    @Test
    void saveBeginsCommitsAndAlwaysClosesEntityManager() {
        FakePersistence persistence = new FakePersistence();
        HibernateEntityRepository<String> repository =
            new HibernateEntityRepository<>(persistence.factory(), String.class);

        repository.save("stored");

        assertEquals(List.of("stored"), persistence.merged);
        assertEquals(1, persistence.beginCount);
        assertEquals(1, persistence.commitCount);
        assertEquals(0, persistence.rollbackCount);
        assertEquals(1, persistence.closeCount);
        assertFalse(persistence.transactionActive);
    }

    @Test
    void findByIdAndFindAllUseReadOnlyEntityManagersAndCloseThem() {
        FakePersistence persistence = new FakePersistence();
        persistence.entities.put(7, "seven");
        persistence.all = List.of("seven", "eight");
        HibernateEntityRepository<String> repository =
            new HibernateEntityRepository<>(persistence.factory(), String.class);

        assertEquals("seven", repository.findById(7).orElseThrow());
        assertTrue(repository.findById(8).isEmpty());
        assertEquals(List.of("seven", "eight"), repository.findAll());

        assertEquals(3, persistence.closeCount);
        assertEquals(0, persistence.beginCount);
        assertEquals(
            List.of("select e from String e"),
            persistence.queries);
    }

    @Test
    void deleteRemovesExistingEntityAndCommitsMissingEntityAsNoOp() {
        FakePersistence persistence = new FakePersistence();
        persistence.entities.put("present", "entity");
        HibernateEntityRepository<String> repository =
            new HibernateEntityRepository<>(persistence.factory(), String.class);

        repository.delete("present");
        repository.delete("missing");

        assertEquals(List.of("entity"), persistence.removed);
        assertEquals(2, persistence.beginCount);
        assertEquals(2, persistence.commitCount);
        assertEquals(0, persistence.rollbackCount);
        assertEquals(2, persistence.closeCount);
    }

    @Test
    void runtimeFailureRollsBackActiveTransactionAndClosesEntityManager() {
        FakePersistence persistence = new FakePersistence();
        persistence.mergeFailure = new IllegalStateException("merge failed");
        HibernateEntityRepository<String> repository =
            new HibernateEntityRepository<>(persistence.factory(), String.class);

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> repository.save("broken"));

        assertEquals("merge failed", failure.getMessage());
        assertEquals(1, persistence.beginCount);
        assertEquals(0, persistence.commitCount);
        assertEquals(1, persistence.rollbackCount);
        assertEquals(1, persistence.closeCount);
        assertFalse(persistence.transactionActive);
    }

    private static final class FakePersistence {
        private final Map<Object, Object> entities = new LinkedHashMap<>();
        private final List<Object> merged = new ArrayList<>();
        private final List<Object> removed = new ArrayList<>();
        private final List<String> queries = new ArrayList<>();
        private List<?> all = List.of();
        private RuntimeException mergeFailure;
        private boolean transactionActive;
        private int beginCount;
        private int commitCount;
        private int rollbackCount;
        private int closeCount;

        private EntityManagerFactory factory() {
            return proxy(EntityManagerFactory.class, (proxy, method, args) -> switch (method.getName()) {
                case "createEntityManager" -> entityManager();
                case "isOpen" -> true;
                case "toString" -> "FakeEntityManagerFactory";
                default -> defaultValue(method.getReturnType());
            });
        }

        private EntityManager entityManager() {
            EntityTransaction transaction = transaction();
            return proxy(EntityManager.class, (proxy, method, args) -> switch (method.getName()) {
                case "getTransaction" -> transaction;
                case "merge" -> {
                    if (mergeFailure != null) {
                        throw mergeFailure;
                    }
                    merged.add(args[0]);
                    yield args[0];
                }
                case "find" -> entities.get(args[1]);
                case "remove" -> {
                    removed.add(args[0]);
                    yield null;
                }
                case "createQuery" -> {
                    queries.add((String) args[0]);
                    yield typedQuery();
                }
                case "close" -> {
                    closeCount++;
                    yield null;
                }
                case "isOpen" -> true;
                case "toString" -> "FakeEntityManager";
                default -> defaultValue(method.getReturnType());
            });
        }

        private EntityTransaction transaction() {
            return proxy(EntityTransaction.class, (proxy, method, args) -> switch (method.getName()) {
                case "begin" -> {
                    transactionActive = true;
                    beginCount++;
                    yield null;
                }
                case "commit" -> {
                    transactionActive = false;
                    commitCount++;
                    yield null;
                }
                case "rollback" -> {
                    transactionActive = false;
                    rollbackCount++;
                    yield null;
                }
                case "isActive" -> transactionActive;
                case "toString" -> "FakeEntityTransaction";
                default -> defaultValue(method.getReturnType());
            });
        }

        private TypedQuery<?> typedQuery() {
            return proxy(TypedQuery.class, (proxy, method, args) -> switch (method.getName()) {
                case "getResultList" -> all;
                case "toString" -> "FakeTypedQuery";
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[] {type}, handler);
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
