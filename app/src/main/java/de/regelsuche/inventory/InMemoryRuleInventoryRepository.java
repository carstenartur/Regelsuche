package de.regelsuche.inventory;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRuleInventoryRepository implements RuleInventoryRepository {
    private final Map<String, ReusableRule> rules = new ConcurrentHashMap<>();
    private final Map<String, String> idsByCanonicalHash = new ConcurrentHashMap<>();

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
        // Preserve insertion order for deterministic exports.
        return new LinkedHashMap<>(rules).values().stream()
            .sorted(Comparator.comparing(ReusableRule::id))
            .toList();
    }
}
