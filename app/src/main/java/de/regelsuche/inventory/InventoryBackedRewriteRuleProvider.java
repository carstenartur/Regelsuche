package de.regelsuche.inventory;

import de.regelsuche.evolution.ExactPolynomialPatternIdentityVerifier;
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

/**
 * Compatibility adapter for unconditional polynomial inventory rules.
 *
 * <p>Stored proof labels are metadata, not execution authority. Every accepted
 * pattern is rechecked by the bounded exact polynomial verifier. Conditional
 * patterns and other mathematical fragments require the condition-aware,
 * replay-authorised learned runtime instead of this legacy adapter.</p>
 */
public class InventoryBackedRewriteRuleProvider {
    private static final Logger LOGGER = Logger.getLogger(InventoryBackedRewriteRuleProvider.class.getName());

    private final RuleInventoryRepository repository;
    private final RuleInventoryConfiguration configuration;
    private final List<RewriteRule> existingRules;
    private final RulePatternParser parser = new RulePatternParser();
    private final ExactPolynomialPatternIdentityVerifier verifier =
        new ExactPolynomialPatternIdentityVerifier();
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
            if (usedIds.contains(ruleId) || usedPatterns.contains(patternKey)) {
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
            if (!reusableRule.assumptions().isEmpty()
                    || !reusableRule.parameterRelations().isEmpty()) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Assumptions and parameter relations require the condition-aware learned runtime"));
                continue;
            }
            PatternExpr left;
            PatternExpr right;
            ExactPolynomialPatternIdentityVerifier.Verification verification;
            try {
                left = toPatternExpr(parser.parse(reusableRule.leftPattern()));
                right = toPatternExpr(parser.parse(reusableRule.rightPattern()));
                Set<String> sourceBindings = new HashSet<>();
                Set<String> unboundTarget = new java.util.TreeSet<>();
                collectPlaceholders(left, sourceBindings);
                collectPlaceholders(right, unboundTarget);
                unboundTarget.removeAll(sourceBindings);
                if (!unboundTarget.isEmpty()) {
                    throw new IllegalArgumentException("Unbound target placeholders: " + unboundTarget);
                }
                // Verify the actual parsed objects that will execute, not a
                // second parser's interpretation or a persisted status label.
                verification = verifier.verify(left, right);
            } catch (IllegalArgumentException exception) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Invalid pattern: " + exception.getMessage()));
                continue;
            }
            if (!verification.proved()) {
                lastDecisions.add(RuleActivationDecision.disabled(reusableRule,
                    "Fresh exact verification: " + verification.status()
                        + " (" + verification.detailCode() + ")"));
                continue;
            }
            activated.add(new PatternRewriteRule(
                ruleId,
                left,
                right,
                RewriteKind.NORMALIZE,
                false,
                0,
                true
            ));
            usedIds.add(ruleId);
            usedPatterns.add(patternKey);
            lastDecisions.add(new RuleActivationDecision(reusableRule, true,
                "Activated after fresh exact polynomial verification: " + verification.proofHash()));
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

    /** Mathematical identity alone does not guarantee an instantiable rewrite target. */
    private static void collectPlaceholders(PatternExpr pattern, Set<String> names) {
        if (pattern instanceof PatternExpr.Placeholder placeholder) {
            names.add(placeholder.name());
        } else if (pattern instanceof PatternExpr.Operation operation) {
            collectPlaceholders(operation.left(), names);
            collectPlaceholders(operation.right(), names);
        } else if (pattern instanceof PatternExpr.Function function) {
            for (PatternExpr argument : function.arguments()) collectPlaceholders(argument, names);
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
