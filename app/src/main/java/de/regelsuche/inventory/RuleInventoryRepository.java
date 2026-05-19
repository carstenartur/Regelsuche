package de.regelsuche.inventory;

import java.util.List;
import java.util.Optional;

public interface RuleInventoryRepository extends AutoCloseable {
    void save(ReusableRule rule);

    List<ReusableRule> findAll();

    default Optional<ReusableRule> findById(String id) {
        return findAll().stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    @Override
    default void close() {
    }
}
