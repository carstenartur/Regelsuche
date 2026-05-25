package de.regelsuche.mining;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Infers a domain tag (e.g. {@code "equations"}, {@code "calculus"}) for a
 * mined macro rule, so Discovery+ entries in the rule inventory carry
 * a stable, queryable domain marker.
 *
 * <p>The inferrer inspects the atomic rule-ids that make up a
 * {@link MacroRuleCandidate}: if the sequence contains an
 * {@code equation_*} step it is tagged {@code "equations"}, an
 * {@code inequality_*} step → {@code "inequalities"}, a
 * {@code calculus_*} step → {@code "calculus"}, a matrix/vector/linear-algebra
 * step → {@code "linear-algebra"}. The default tag is {@code "algebra"}.</p>
 *
 * <p>This is intentionally rule-id-driven (not pattern-string parsing) so
 * the tag stays stable even when surface expressions look algebraic — the
 * {@code equation_subtract_both_sides → equation_divide_both_sides} chain
 * for {@code 2*x + 3 = 7 → x = 2} is clearly equation-domain even though
 * both sides look like ordinary terms.</p>
 */
public final class MacroDomainInferrer {

    /** Domain tag for plain term/algebraic macros. */
    public static final String ALGEBRA = "algebra";
    public static final String EQUATIONS = "equations";
    public static final String INEQUALITIES = "inequalities";
    public static final String CALCULUS = "calculus";
    public static final String LINEAR_ALGEBRA = "linear-algebra";

    /**
     * @return the inferred domain tag for {@code candidate}. Never null.
     */
    public String inferDomain(MacroRuleCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return inferFromRuleIds(candidate.ruleIdSequence());
    }

    /**
     * Variant that operates on a raw rule-id sequence – useful for callers
     * that have not constructed a {@link MacroRuleCandidate} yet
     * (e.g. {@code MacroRuleLearningService}).
     */
    public String inferFromRuleIds(List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty()) {
            return ALGEBRA;
        }
        // Priority order: inequalities and equations are more specific than
        // calculus / linear-algebra (an inequality manipulation that uses an
        // algebraic factorisation should still be tagged "inequalities"),
        // and any of those override the generic "algebra" tag.
        boolean inequality = false;
        boolean equation = false;
        boolean calculus = false;
        boolean linalg = false;
        for (String raw : ruleIds) {
            if (raw == null) {
                continue;
            }
            String id = raw.toLowerCase(Locale.ROOT);
            if (id.startsWith("inequality_")) {
                inequality = true;
            } else if (id.startsWith("equation_")) {
                equation = true;
            } else if (id.startsWith("calculus_")) {
                calculus = true;
            } else if (id.startsWith("linalg_")
                || id.startsWith("matrix_")
                || id.startsWith("vector_")) {
                linalg = true;
            }
        }
        if (inequality) {
            return INEQUALITIES;
        }
        if (equation) {
            return EQUATIONS;
        }
        if (calculus) {
            return CALCULUS;
        }
        if (linalg) {
            return LINEAR_ALGEBRA;
        }
        return ALGEBRA;
    }
}
