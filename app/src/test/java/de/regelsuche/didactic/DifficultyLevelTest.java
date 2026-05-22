package de.regelsuche.didactic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DifficultyLevelTest {

    @Test
    void difficultyLevelRestrictsAdvancedRules() {
        // GRUNDSCHULE only knows neutral-element / zero-multiplication rules.
        DifficultyLevel grade = DifficultyLevel.GRUNDSCHULE;
        assertTrue(grade.permits("ast_add_zero_right"));
        assertFalse(grade.permits("ast_distribute_left_add"),
            "Distributivgesetz is not a Grundschule rule");
        assertFalse(grade.permits("calc_chain_rule"),
            "Kettenregel must be locked out at Grundschule level");
        assertFalse(grade.permits("equality-saturation"));

        // MITTELSTUFE adds distributivity and basic fractions.
        DifficultyLevel middle = DifficultyLevel.MITTELSTUFE;
        assertTrue(middle.permits("ast_distribute_left_add"));
        assertTrue(middle.permits("rational_cancel_common_factor"));
        assertFalse(middle.permits("calc_chain_rule"));

        // OBERSTUFE adds calculus basics and trig.
        DifficultyLevel upper = DifficultyLevel.OBERSTUFE;
        assertTrue(upper.permits("calc_power_rule"));
        assertTrue(upper.permits("trig_pythagoras"));
        assertFalse(upper.permits("calc_chain_rule"));
        assertFalse(upper.permits("equality-saturation"));

        // UNIVERSITAET unlocks symbolic methods.
        DifficultyLevel uni = DifficultyLevel.UNIVERSITAET;
        assertTrue(uni.permits("calc_chain_rule"));
        assertTrue(uni.permits("equality-saturation"));
        assertTrue(uni.permits("macro_binomial_square"),
            "macro-rule prefix wildcard should match");

        // EXPERTE always permits.
        DifficultyLevel expert = DifficultyLevel.EXPERTE;
        assertTrue(expert.permits("totally_unknown_rule_id"));
    }

    @Test
    void rankIsMonotonic() {
        DifficultyLevel[] values = DifficultyLevel.values();
        for (int i = 1; i < values.length; i++) {
            assertTrue(values[i].rank() > values[i - 1].rank(),
                "ranks must be strictly increasing");
        }
    }
}
