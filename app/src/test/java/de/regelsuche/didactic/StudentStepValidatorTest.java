package de.regelsuche.didactic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.equivalence.EquivalenceService;
import org.junit.jupiter.api.Test;

class StudentStepValidatorTest {

    /** A trivial in-memory equivalence service for deterministic tests. */
    private static final class FixedEquivalence implements EquivalenceService {
        private final boolean answer;
        FixedEquivalence(boolean answer) { this.answer = answer; }
        @Override public boolean areEquivalent(String l, String r) { return answer; }
    }

    @Test
    void studentStepValidationRejectsWrongTransformation() {
        // The student "simplified" (a + b)/b to just a — a classical
        // misconception. The validator must reject and surface the rule.
        StudentStepValidator validator =
            new StudentStepValidator(new FixedEquivalence(false));

        StudentStepValidator.Result result =
            validator.validate("(a + b) / b", "a");

        assertFalse(result.correct(),
            "result.correct() must be false for non-equivalent step");
        assertTrue(result.misconception().isPresent(),
            "the validator should surface the misconception rule");
        assertTrue(result.message().toLowerCase().contains("typischer fehler"),
            "the message should clearly tell the student a misconception was detected");
    }

    @Test
    void studentStepValidationAcceptsEquivalentStep() {
        // (x + 0) is equivalent to x; the validator must accept it.
        StudentStepValidator validator =
            new StudentStepValidator(new FixedEquivalence(true));

        StudentStepValidator.Result result =
            validator.validate("x + 0", "x");

        assertTrue(result.correct(), "equivalent step must be accepted");
        assertTrue(result.misconception().isEmpty());
    }

    @Test
    void studentStepValidationRejectsEmptyInput() {
        StudentStepValidator validator =
            new StudentStepValidator(new FixedEquivalence(true));

        StudentStepValidator.Result result = validator.validate("x", "");
        assertFalse(result.correct());
    }
}
