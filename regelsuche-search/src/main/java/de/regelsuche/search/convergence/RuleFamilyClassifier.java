package de.regelsuche.search.convergence;

import java.util.Locale;

/** Lightweight taxonomy used to make generated graph labels understandable. */
public final class RuleFamilyClassifier {
    public RuleFamily classify(String ruleId) {
        String rule = ruleId == null ? "" : ruleId.toLowerCase(Locale.ROOT);
        if (rule.isBlank()) {
            return RuleFamily.OTHER;
        }
        if (rule.startsWith("macro_")) {
            return RuleFamily.LEARNED_MACRO;
        }
        if (rule.contains("complete_square") || rule.contains("complete-square")) {
            return RuleFamily.COMPLETE_SQUARE;
        }
        if (rule.contains("hypothesis_difference_of_squares_preparation")
            || rule.contains("hidden_structure")
            || rule.contains("hidden-structure")) {
            return RuleFamily.HIDDEN_STRUCTURE;
        }
        if (rule.contains("square_difference_factor")
            || rule.contains("factor")
            || rule.contains("collect")) {
            return RuleFamily.FACTORIZATION;
        }
        if (rule.contains("telescop")) {
            return RuleFamily.TELESCOPING;
        }
        if (rule.contains("rationaliz")) {
            return RuleFamily.RATIONALIZATION;
        }
        if (rule.contains("expand") || rule.contains("distribute")) {
            return RuleFamily.EXPANSION;
        }
        if (rule.contains("normalize") || rule.contains("canonical") || rule.contains("linear_offset_simplify")) {
            return RuleFamily.NORMALIZATION;
        }
        return RuleFamily.OTHER;
    }
}
