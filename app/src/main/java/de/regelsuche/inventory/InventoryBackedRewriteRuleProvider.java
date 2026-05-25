package de.regelsuche.inventory;

import de.regelsuche.validation.CandidateProofStatus;
import de.regelsuche.mining.PatternBinary;
import de.regelsuche.mining.PatternFunction;
import de.regelsuche.mining.PatternNumber;
import de.regelsuche.mining.PatternVariable;
import de.regelsuche.mining.RulePatternNode;
import de.regelsuche.mining.RulePatternParser;
import de.regelsuche.transform.PatternExpr;
import de.regelsuche.transform.PatternRewriteRule;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.transform.RewriteRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class InventoryBackedRewriteRuleProvider {
    private static final Logger LOGGER = Logger.getLogger(InventoryBackedRewriteRuleProvider.class.getName());

    private final RuleInventoryRepository repository;
    private final RuleInventoryConfiguration configuration;
    private final List<RewriteRule> existingRules;
    private final RulePatternParser parser = new RulePatternParser();
    private final List<RuleActivationDecision> lastDecisions = new ArrayList<>();

    public InventoryBackedRewriteRuleProvider(
        RuleInventoryRepository repository,
        RuleInventoryConfiguration configuration,
        List<RewriteRule> existingRules
    ) {
        this.repository = repository;
        this.configuration = configuration;
        this.existingRules = List.copyOf(existingRules);
    }

    public List<RewriteRule> activatedRules() {
        lastDecisions.clear();
        if (!configuration.enabled()) {
            for (ReusableRule reusableRule : repository.findAll()) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule, "Inventory disabled"));
            }
            logDecisions();
            return List.of();
        }
        Set<String> usedIds = new HashSet<>();
        Set<String> usedPatterns = new HashSet<>();
        for (RewriteRule existingRule : existingRules) {
            usedIds.add(existingRule.id());
        }

        List<RewriteRule> activated = new ArrayList<>();
        for (ReusableRule reusableRule : repository.findAll()) {
            String ruleId = "inventory_" + reusableRule.id();
            String patternKey = reusableRule.leftPattern() + " -> " + reusableRule.rightPattern();
            if (reusableRule.proofStatus().ordinal() < configuration.minProofStatus().ordinal()) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Proof status " + reusableRule.proofStatus() + " below minimum "
                        + configuration.minProofStatus()));
                continue;
            }
            if (!configuration.allows(reusableRule.id()) || !repository.isEnabled(reusableRule.id())) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Rule id " + reusableRule.id() + " not allowed by allow/deny/disabled list"));
                continue;
            }
            if (usedIds.contains(ruleId) || !usedPatterns.add(patternKey)) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Rule conflicts with an existing rewrite rule or duplicate pattern"));
                continue;
            }
            if (reusableRule.averageImprovement() <= 0) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Average improvement is non-positive (" + reusableRule.averageImprovement() + ")"));
                continue;
            }
            if (reusableRule.leftPattern().equals(reusableRule.rightPattern())) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Left and right pattern are identical"));
                continue;
            }
            int complexityIncrease = estimateComplexityIncrease(reusableRule);
            if (complexityIncrease > configuration.maxComplexityIncrease()) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Estimated complexity increase " + complexityIncrease
                        + " exceeds limit " + configuration.maxComplexityIncrease()));
                continue;
            }
            activated.add(new PatternRewriteRule(
                ruleId,
                toPatternExpr(parser.parse(reusableRule.leftPattern())),
                toPatternExpr(parser.parse(reusableRule.rightPattern())),
                RewriteKind.NORMALIZE,
                false,
                0,
                true
            ));
            usedIds.add(ruleId);
            lastDecisions.add(RuleActivationDecision.activated(reusableRule));
        }
        logDecisions();
        return activated;
    }

    public List<RuleActivationDecision> lastDecisions() {
        return List.copyOf(lastDecisions);
    }

    private int estimateComplexityIncrease(ReusableRule rule) {
        return rule.rightPattern().length() - rule.leftPattern().length();
    }

    private void logDecisions() {
        for (RuleActivationDecision decision : lastDecisions) {
            if (!decision.activated()) {
                LOGGER.fine(() -> "Inventory rule " + decision.rule().id()
                    + " not activated: " + decision.reason());
            }
        }
    }

    private PatternExpr toPatternExpr(RulePatternNode node) {
        if (node instanceof PatternNumber number) {
            return PatternExpr.num(number.value());
        }
        if (node instanceof PatternVariable variable) {
            return PatternExpr.var(variable.name());
        }
        if (node instanceof PatternFunction function) {
            PatternExpr[] converted = new PatternExpr[function.arguments().size()];
            for (int i = 0; i < converted.length; i++) {
                converted[i] = toPatternExpr(function.arguments().get(i));
            }
            return PatternExpr.fn(function.name(), converted);
        }
        PatternBinary binary = (PatternBinary) node;
        return PatternExpr.op(binary.op(), toPatternExpr(binary.left()), toPatternExpr(binary.right()));
    }
}
