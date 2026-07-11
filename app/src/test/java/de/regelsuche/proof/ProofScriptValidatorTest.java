package de.regelsuche.proof;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProofScriptValidatorTest {

    @Test
    void validatesCleanScript() {
        String script = "theorem a_eq_b : a = b := by ring";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "lean4");
        assertTrue(result.isValid());
        assertFalse(result.hasAdmittedStatement());
    }

    @Test
    void detectsSorryInLean() {
        String script = "theorem a_eq_b : a = b := by\n  sorry\n";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "lean4");
        assertFalse(result.isValid());
        assertTrue(result.hasAdmittedStatement());
    }

    @Test
    void detectsAdmitKeyword() {
        String script = "theorem a_eq_b : a = b := by\n  admit\n";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "lean4");
        assertFalse(result.isValid());
        assertTrue(result.hasAdmittedStatement());
    }

    @Test
    void ignoresSorryInComments() {
        String script = "-- sorry this is just a comment\ntheorem a_eq_b : a = b := by ring\n";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "lean4");
        assertTrue(result.isValid(), "sorry in comment should not count as admitted statement");
    }

    @Test
    void detectsUnsupportedPlaceholderInLean() {
        String script = "theorem a_eq_b : a = b := by\n  rw [_]\n";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "lean4");
        assertFalse(result.isValid());
        assertTrue(result.hasUnsupportedPlaceholder());
    }

    @Test
    void smtlibScriptDoesNotFlagUnderscore() {
        // In SMT-LIB, _ is used for indexed identifiers ((_ BitVec 32)), not a placeholder
        String script = "(assert (= (_ x 1) (_ y 1)))";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "smtlib2");
        assertTrue(result.isValid(), "SMT-LIB underscore should not trigger placeholder warning");
    }

    @Test
    void detectsTacticHoleInLean() {
        String script = "theorem a_eq_b : a = b := by\n  exact ?goal\n";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "lean4");
        assertFalse(result.isValid());
        assertTrue(result.hasUnsupportedPlaceholder(),
            "?goal tactic hole should be flagged as an unsupported placeholder");
    }

    @Test
    void detectsTacticHoleAtLineStart() {
        String script = "theorem a_eq_b : a = b := by\n?goal\n";
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(script, "lean4");
        assertFalse(result.isValid());
        assertTrue(result.hasUnsupportedPlaceholder());
    }

    @Test
    void rejectsEmptyScript() {
        ProofScriptValidator.ValidationResult result = ProofScriptValidator.validate(null, "lean4");
        assertFalse(result.isValid());
        assertTrue(result.violations().contains("empty-script"));

        result = ProofScriptValidator.validate("   ", "lean4");
        assertFalse(result.isValid());
        assertTrue(result.violations().contains("empty-script"));
    }

    @Test
    void scriptGeneratedByLeanBridgeContainsSorry() {
        LeanProofBridge bridge = new LeanProofBridge();
        ProofBridge.ProofAttempt attempt = bridge.prove("a + b", "b + a",
            java.util.List.of());
        ProofScriptValidator.ValidationResult result =
            ProofScriptValidator.validate(attempt.artifact(), attempt.tool());
        // The skeleton generator always emits sorry — this is expected and
        // confirms that SCRIPT_GENERATED never satisfies a proof policy.
        assertTrue(result.hasAdmittedStatement(),
            "LeanProofBridge skeleton should contain sorry (admitted statement)");
    }
}
