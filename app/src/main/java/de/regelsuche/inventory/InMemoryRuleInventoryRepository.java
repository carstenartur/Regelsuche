package de.regelsuche.inventory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRuleInventoryRepository implements RuleInventoryRepository {
    private final Map<String, ReusableRule> rules = new ConcurrentHashMap<>();

    @Override
    public void save(ReusableRule rule) {
        rules.put(rule.id(), rule);
    }

    @Override
    public List<ReusableRule> findAll() {
        return rules.values().stream().sorted(Comparator.comparing(ReusableRule::id)).toList();
    }
}
