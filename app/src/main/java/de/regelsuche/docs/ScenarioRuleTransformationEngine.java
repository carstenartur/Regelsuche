package de.regelsuche.docs;

import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationEngine;
import java.util.ArrayList;
import java.util.List;

final class ScenarioRuleTransformationEngine implements TransformationEngine {
    private final List<ScenarioRule> rules;

    ScenarioRuleTransformationEngine(List<ScenarioRulePack> packs) {
        this.rules = packs.stream().flatMap(pack -> pack.rules().stream()).toList();
    }

    @Override
    public List<Transformation> transform(String expression) {
        String canonical = canonical(expression);
        List<Transformation> transformations = new ArrayList<>();
        for (ScenarioRule rule : rules) {
            if (!rule.active()) {
                continue;
            }
            if (canonical(rule.from()).equals(canonical)) {
                transformations.add(new Transformation(
                        rule.id(),
                        rule.to(),
                        rule.kind(),
                        rule.costDelta() > 0,
                        rule.costDelta(),
                        true,
                        rule.id() + ":" + rule.to()));
            }
        }
        return transformations;
    }

    private String canonical(String expression) {
        return expression == null ? "" : expression.replaceAll("\\s+", "");
    }
}
