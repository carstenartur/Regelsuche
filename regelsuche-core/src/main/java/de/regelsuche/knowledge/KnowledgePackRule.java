package de.regelsuche.knowledge;

import java.util.List;

public record KnowledgePackRule(
        String id,
        String origin,
        String derivation,
        RuleStatus status,
        List<SearchEffect> searchEffects) {
    public KnowledgePackRule {
        searchEffects = searchEffects == null ? List.of() : List.copyOf(searchEffects);
    }
}
