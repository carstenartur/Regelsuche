package de.regelsuche.search;

import de.regelsuche.knowledge.SearchEffect;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class BridgeAnalyticsService {
    public Map<String, Long> bridgeUsage(
            Map<String, Long> ruleUsage,
            Map<String, Set<SearchEffect>> ruleEffects) {
        return ruleUsage.entrySet().stream()
                .filter(entry -> ruleEffects.getOrDefault(entry.getKey(), Set.of()).contains(SearchEffect.BRIDGING))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (left, right) -> left, java.util.LinkedHashMap::new));
    }
}
