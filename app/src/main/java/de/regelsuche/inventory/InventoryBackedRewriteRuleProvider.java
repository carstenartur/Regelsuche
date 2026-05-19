package de.regelsuche.inventory;

import de.regelsuche.mining.CandidateProofStatus;
import de.regelsuche.mining.PatternBinary;
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

public class InventoryBackedRewriteRuleProvider {
    private final RuleInventoryRepository repository;
    private final RuleInventoryConfiguration configuration;
    private final List<RewriteRule> existingRules;
    private final RulePatternParser parser = new RulePatternParser();

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
        if (!configuration.enabled()) {
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
            if (reusableRule.proofStatus().ordinal() < configuration.minProofStatus().ordinal()
                || usedIds.contains(ruleId)
                || !usedPatterns.add(patternKey)
                || reusableRule.averageImprovement() <= 0
                || reusableRule.leftPattern().equals(reusableRule.rightPattern())) {
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
        }
        return activated;
    }

    private PatternExpr toPatternExpr(RulePatternNode node) {
        if (node instanceof PatternNumber number) {
            return PatternExpr.num(number.value());
        }
        if (node instanceof PatternVariable variable) {
            return PatternExpr.var(variable.name());
        }
        PatternBinary binary = (PatternBinary) node;
        return PatternExpr.op(binary.op(), toPatternExpr(binary.left()), toPatternExpr(binary.right()));
    }
}
