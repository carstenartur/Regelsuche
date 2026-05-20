package de.regelsuche.inventory;

import de.regelsuche.export.ExportBundle;
import de.regelsuche.mining.CandidateProofStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * Enable or disable a rule by id. Disabled rules are still returned by
     * {@link #findAll()} (and exported) but are skipped by activation/lookup
     * helpers such as {@link #findEnabled()}. The default implementation is a
     * no-op for repositories that do not track an enabled flag.
     */
    default void setEnabled(String ruleId, boolean enabled) {
        // no-op for repositories that don't track an enabled flag
    }

    /** @return whether the rule with {@code ruleId} is enabled (default: true). */
    default boolean isEnabled(String ruleId) {
        return true;
    }

    /** @return all rules that are currently enabled. */
    default List<ReusableRule> findEnabled() {
        return findAll().stream().filter(rule -> isEnabled(rule.id())).toList();
    }

    /** Associate a free-form domain tag (e.g. {@code "polynomial"}) with a rule. */
    default void addTag(String ruleId, String tag) {
        // no-op for repositories that don't track tags
    }

    default void removeTag(String ruleId, String tag) {
        // no-op for repositories that don't track tags
    }

    /** @return tags previously associated with {@code ruleId}. */
    default Set<String> tagsOf(String ruleId) {
        return Set.of();
    }

    /** @return rule ids carrying the given tag. */
    default List<ReusableRule> findByTag(String tag) {
        return findAll().stream().filter(rule -> tagsOf(rule.id()).contains(tag)).toList();
    }

    @Override
    default void close() {
    }
}
