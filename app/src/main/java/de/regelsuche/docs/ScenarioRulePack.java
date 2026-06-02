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
        String family,
        ScenarioRuleStatus status,
        boolean enabledByDefault,
        List<String> examples) {
    ScenarioRule {
        effects = effects == null ? List.of() : List.copyOf(effects);
        kind = kind == null ? RewriteKind.NORMALIZE : kind;
        family = family == null ? "" : family;
        status = status == null ? ScenarioRuleStatus.VALIDATED : status;
        examples = examples == null ? List.of() : List.copyOf(examples);
    }

    boolean active() {
        return enabledByDefault && status == ScenarioRuleStatus.VALIDATED && !examples.isEmpty();
    }
}

enum ScenarioRuleStatus {
    CANDIDATE,
    VALIDATED
}
