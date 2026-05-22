package de.regelsuche.didactic;

import java.util.Set;

/**
 * Coarse-grained school/study level. Used by the didactic layer to:
 *
 * <ul>
 *   <li>filter the rule set offered to the search (no derivatives in
 *       primary school, no symbolic logarithms before {@link #OBERSTUFE},
 *       …) — see {@link #permits(String)};</li>
 *   <li>tune the verbosity of {@link ExplanationGenerator}-style
 *       hints;</li>
 *   <li>cap expression complexity inside {@link DidacticCostModel}.</li>
 * </ul>
 *
 * <p>The allow-list is intentionally conservative: rules without an
 * explicit minimum level default to {@link #MITTELSTUFE}. This matches
 * spec item 6 ("erlaubte Regeln, Ausdruckskomplexität, Erklärungstiefe,
 * Abkürzungen, Formalitätsgrad").</p>
 */
public enum DifficultyLevel {

    /** Grundschule — only arithmetic with concrete numbers, no variables required. */
    GRUNDSCHULE(1, Set.of(
        "ast_add_zero_left", "ast_add_zero_right",
        "ast_multiply_one_left", "ast_multiply_one_right",
        "ast_multiply_zero_left", "ast_multiply_zero_right"
    )),

    /** Mittelstufe — basic algebra, distributivity, combining like terms, simple fractions. */
    MITTELSTUFE(2, Set.of(
        "ast_add_zero_left", "ast_add_zero_right",
        "ast_multiply_one_left", "ast_multiply_one_right",
        "ast_multiply_zero_left", "ast_multiply_zero_right",
        "ast_distribute_left_add", "ast_distribute_right_add",
        "ast_distribute_left_subtract", "ast_distribute_right_subtract",
        "ast_double_term", "ast_product_to_power_two", "ast_power_two_to_product",
        "ast_factor_common_left", "ast_factor_common_right",
        "polynomial_combine_like_terms",
        "rational_cancel_common_factor", "rational_multiply_fractions", "rational_divide_by_fraction"
    )),

    /** Oberstufe — adds powers, trig identities, logs, basic calculus. */
    OBERSTUFE(3, Set.of(
        // everything from MITTELSTUFE is automatically permitted (see permits)
        "ast_combine_powers", "ast_power_of_power",
        "ast_canonical_normalize",
        "trig_pythagoras", "trig_double_angle",
        "log_product", "log_quotient", "log_power",
        "calc_power_rule", "calc_sum_rule", "calc_product_rule",
        "radical_sqrt_square", "radical_product"
    )),

    /** Universität — symbolic methods, equality saturation, advanced rewriting. */
    UNIVERSITAET(4, Set.of(
        "equality-saturation",
        "calc_chain_rule", "calc_quotient_rule",
        "matrix_distribute",
        "macro_*"
    )),

    /** Experte — everything; reserved for research / discovery mode. */
    EXPERTE(5, Set.of());

    private final int rank;
    private final Set<String> additionalRuleIds;

    DifficultyLevel(int rank, Set<String> additionalRuleIds) {
        this.rank = rank;
        this.additionalRuleIds = Set.copyOf(additionalRuleIds);
    }

    /** Numerical order so that callers can do {@code a.rank() >= b.rank()}. */
    public int rank() {
        return rank;
    }

    /**
     * @return {@code true} iff a rule with the given id is appropriate for
     *         this level. Higher levels inherit lower-level rules.
     *         {@link #EXPERTE} permits everything.
     */
    public boolean permits(String ruleId) {
        if (ruleId == null || ruleId.isEmpty()) {
            return true;
        }
        if (this == EXPERTE) {
            return true;
        }
        for (DifficultyLevel level : values()) {
            if (level.rank > this.rank) {
                continue;
            }
            if (level.additionalRuleIds.contains(ruleId)) {
                return true;
            }
            for (String pattern : level.additionalRuleIds) {
                if (pattern.endsWith("*")
                    && ruleId.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            }
        }
        return false;
    }
}
