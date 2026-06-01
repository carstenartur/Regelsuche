package de.regelsuche.docs;

import de.regelsuche.knowledge.SearchEffect;
import de.regelsuche.transform.RewriteKind;
import java.util.List;

record ScenarioRulePack(String id, List<ScenarioRule> rules) {
    ScenarioRulePack {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}

record ScenarioRule(
        String id,
        String from,
        String to,
        RewriteKind kind,
        int costDelta,
        List<SearchEffect> effects,
        String family) {
    ScenarioRule {
        effects = effects == null ? List.of() : List.copyOf(effects);
        kind = kind == null ? RewriteKind.NORMALIZE : kind;
        family = family == null ? "" : family;
    }
}
