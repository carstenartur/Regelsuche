package de.regelsuche.inventory;

/**
 * Records why a reusable rule from the inventory was or wasn't activated as a
 * rewrite rule. Allows API consumers and operators to surface clear,
 * explainable decisions.
 */
public record RuleActivationDecision(ReusableRule rule, boolean activated, String reason) {
    public static RuleActivationDecision activated(ReusableRule rule) {
        return new RuleActivationDecision(rule, true, "Activated");
    }

    public static RuleActivationDecision disabled(ReusableRule rule, String reason) {
        return new RuleActivationDecision(rule, false, reason);
    }
}
