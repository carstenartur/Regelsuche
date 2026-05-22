package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MacroDomainInferrerTest {

    private final MacroDomainInferrer inferrer = new MacroDomainInferrer();

    @Test
    void inequalityRuleWinsOverEquationRule() {
        // Inequalities are more specific than equations; the comparator-flip
        // makes the resulting macro an inequality manipulation even when an
        // equation_* helper also appears in the witness.
        assertEquals("inequalities",
            inferrer.inferFromRuleIds(List.of(
                "equation_subtract_both_sides",
                "inequality_divide_both_sides")));
    }

    @Test
    void equationRulesYieldEquationsTag() {
        assertEquals("equations",
            inferrer.inferFromRuleIds(List.of(
                "equation_subtract_both_sides",
                "equation_divide_both_sides")));
    }

    @Test
    void calculusRulesYieldCalculusTag() {
        assertEquals("calculus",
            inferrer.inferFromRuleIds(List.of(
                "calculus_diff_power_rule",
                "calculus_diff_of_constant")));
    }

    @Test
    void linalgRulesYieldLinearAlgebraTag() {
        assertEquals("linear-algebra",
            inferrer.inferFromRuleIds(List.of(
                "linalg_distributivity",
                "matrix_add",
                "vector_scale")));
    }

    @Test
    void plainAlgebraRulesFallBackToAlgebra() {
        assertEquals("algebra",
            inferrer.inferFromRuleIds(List.of(
                "distributivity_apply",
                "combine_like_terms")));
    }

    @Test
    void emptyOrNullRuleListsFallBackToAlgebra() {
        assertEquals("algebra", inferrer.inferFromRuleIds(List.of()));
        assertEquals("algebra", inferrer.inferFromRuleIds(null));
    }

    @Test
    void candidateOverloadDelegatesToRuleIdInference() {
        MacroRuleCandidate calculus = new MacroRuleCandidate(
            "macro:calc",
            List.of("calculus_diff_power_rule", "combine_like_terms"),
            3,
            "diff(x ^ 3, x)",
            "3 * x ^ 2",
            2.0,
            CandidateProofStatus.OBSERVED,
            List.of()
        );
        assertEquals("calculus", inferrer.inferDomain(calculus));
    }
}
