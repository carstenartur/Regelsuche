package de.regelsuche.persistence.relational;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public class HibernateEntityRepository<T> {
    private final EntityManagerFactory entityManagerFactory;
    private final Class<T> entityClass;

    public HibernateEntityRepository(EntityManagerFactory entityManagerFactory, Class<T> entityClass) {
        this.entityManagerFactory = entityManagerFactory;
        this.entityClass = entityClass;
    }

    public void save(T entity) {
        inTransaction(entityManager -> entityManager.merge(entity));
    }

    public Optional<T> findById(Object id) {
        return withEntityManager(entityManager -> Optional.ofNullable(entityManager.find(entityClass, id)));
    }

    public List<T> findAll() {
        return withEntityManager(entityManager -> entityManager
            .createQuery("select e from " + entityClass.getSimpleName() + " e", entityClass)
            .getResultList());
    }

    public void delete(Object id) {
        inTransaction(entityManager -> {
            T entity = entityManager.find(entityClass, id);
            if (entity != null) {
                entityManager.remove(entity);
            }
        });
    }

    protected void inTransaction(Consumer<EntityManager> work) {
        withEntityManager(entityManager -> {
            entityManager.getTransaction().begin();
            try {
                work.accept(entityManager);
                entityManager.getTransaction().commit();
            } catch (RuntimeException exception) {
                if (entityManager.getTransaction().isActive()) {
                    entityManager.getTransaction().rollback();
                }
                throw exception;
            }
            return null;
        });
    }

    protected <R> R withEntityManager(Function<EntityManager, R> work) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            return work.apply(entityManager);
        } finally {
            entityManager.close();
        }
    }
}
