package de.regelsuche.search;

import de.regelsuche.knowledge.KnowledgePackRule;
import java.util.Map;

public final class ReplayProvenanceFormatter {
    private final Map<String, KnowledgePackRule> rulesById;

    public ReplayProvenanceFormatter(Map<String, KnowledgePackRule> rulesById) {
        this.rulesById = Map.copyOf(rulesById);
    }

    public String describe(String ruleId) {
        KnowledgePackRule rule = rulesById.get(ruleId);
        if (rule == null) {
            return "Rule: " + ruleId + "\nPack: UNKNOWN\nOrigin: UNKNOWN\nDerivation: UNKNOWN\nStatus: UNKNOWN";
        }
        String pack = rule.id().contains(".") ? rule.id().substring(0, rule.id().lastIndexOf('.')) : rule.id();
        return "Rule: " + rule.id()
                + "\nPack: " + pack
                + "\nOrigin: " + rule.origin()
                + "\nDerivation: " + rule.derivation()
                + "\nStatus: " + rule.status();
    }
}
