package de.regelsuche.mining;

import java.util.List;

public class KnownRuleRepository {
    private final KnownRuleSimilarityService similarityService = new KnownRuleSimilarityService();
    private final List<KnownRule> rules = List.of(
        new KnownRule("erste binomische Formel", "a^2 + 2*a*b + b^2", "(a + b)^2"),
        new KnownRule("erste binomische Formel", "x^2 + 2*a*x + a^2", "(x + a)^2"),
        new KnownRule("zweite binomische Formel", "a^2 - 2*a*b + b^2", "(a - b)^2"),
        new KnownRule("zweite binomische Formel", "x^2 - 2*a*x + a^2", "(x - a)^2"),
        new KnownRule("dritte binomische Formel", "(a + b)*(a - b)", "a^2 - b^2"),
        new KnownRule("quadratische Ergänzung plus", "x^2 + 2*a*x", "(x + a)^2 - a^2"),
        new KnownRule("quadratische Ergänzung minus", "x^2 - 2*a*x", "(x - a)^2 - a^2")
    );

    public RuleStatus statusFor(String leftPattern, String rightPattern) {
        String candidate = RulePatternCanonicalizer.hash(leftPattern, rightPattern);
        for (KnownRule rule : rules) {
            if (candidate.equals(RulePatternCanonicalizer.hash(rule.leftPattern(), rule.rightPattern()))
                || candidate.equals(RulePatternCanonicalizer.hash(rule.rightPattern(), rule.leftPattern()))) {
                return RuleStatus.MATCHES_KNOWN_RULE;
            }
        }
        return RuleStatus.NEW;
    }

    public double similarityToKnownRules(String leftPattern, String rightPattern) {
        return similarityService.similarityToKnownRules(leftPattern, rightPattern, rules);
    }

    public List<KnownRule> all() {
        return rules;
    }
}
