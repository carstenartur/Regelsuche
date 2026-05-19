package de.regelsuche.inventory;

import de.regelsuche.export.ExportBundle;
import de.regelsuche.mining.CandidateProofStatus;
import java.util.List;
import java.util.Optional;

public interface RuleInventoryRepository extends AutoCloseable {
    void save(ReusableRule rule);

    List<ReusableRule> findAll();

    default Optional<ReusableRule> findById(String id) {
        return findAll().stream().filter(rule -> rule.id().equals(id)).findFirst();
    }

    default List<ReusableRule> findByStatus(CandidateProofStatus proofStatus) {
        return findAll().stream()
            .filter(rule -> rule.proofStatus() == proofStatus)
            .toList();
    }

    default List<ReusableRule> findReusable(CandidateProofStatus minProofStatus) {
        return findAll().stream()
            .filter(rule -> rule.proofStatus().ordinal() >= minProofStatus.ordinal())
            .toList();
    }

    default void saveAll(List<ReusableRule> rules) {
        rules.forEach(this::save);
    }

    default void importBundle(ExportBundle bundle) {
        saveAll(bundle.reusableRules());
    }

    default ExportBundle exportBundle() {
        return ExportBundle.of(List.of(), List.of(), findAll());
    }

    @Override
    default void close() {
    }
}
