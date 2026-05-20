package de.regelsuche.inventory;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRuleInventoryRepository implements RuleInventoryRepository {
    private final Map<String, ReusableRule> rules = new ConcurrentHashMap<>();
    private final Map<String, String> idsByCanonicalHash = new ConcurrentHashMap<>();
    private final Set<String> disabledIds = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> tagsByRuleId = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(ReusableRule rule) {
        String hash = rule.canonicalHash();
        if (hash != null && !hash.isBlank()) {
            String existingId = idsByCanonicalHash.get(hash);
            if (existingId != null && !existingId.equals(rule.id())) {
                // Duplicate by canonical hash: keep the existing entry, bump its usage instead.
                ReusableRule existing = rules.get(existingId);
                if (existing != null) {
                    rules.put(existingId, existing.withUsage(Instant.now(), existing.usageCount() + 1));
                }
                return;
            }
            idsByCanonicalHash.put(hash, rule.id());
        }
        rules.put(rule.id(), rule);
    }

    @Override
    public List<ReusableRule> findAll() {
        // Sort by id so exports are deterministic regardless of the underlying map's iteration order.
        return rules.values().stream()
            .sorted(Comparator.comparing(ReusableRule::id))
            .toList();
    }

    @Override
    public void setEnabled(String ruleId, boolean enabled) {
        if (enabled) {
            disabledIds.remove(ruleId);
        } else {
            disabledIds.add(ruleId);
        }
    }

    @Override
    public boolean isEnabled(String ruleId) {
        return !disabledIds.contains(ruleId);
    }

    @Override
    public void addTag(String ruleId, String tag) {
        tagsByRuleId.computeIfAbsent(ruleId, key -> ConcurrentHashMap.newKeySet()).add(tag);
    }

    @Override
    public void removeTag(String ruleId, String tag) {
        Set<String> tags = tagsByRuleId.get(ruleId);
        if (tags != null) {
            tags.remove(tag);
        }
    }

    @Override
    public Set<String> tagsOf(String ruleId) {
        Set<String> tags = tagsByRuleId.get(ruleId);
        if (tags == null) {
            return Set.of();
        }
        return Set.copyOf(new LinkedHashSet<>(tags));
    }
}
