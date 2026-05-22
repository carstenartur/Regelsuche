package de.regelsuche.didactic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MisconceptionDetectorTest {

    private final MisconceptionDetector detector = new MisconceptionDetector();

    @Test
    void misconceptionRuleDetectsFalseCancellation() {
        // (a + b) / b -> a is the classical false-cancellation mistake.
        Optional<MisconceptionRule> rule = detector.detectTermStep("(a + b) / b", "a");
        assertTrue(rule.isPresent(),
            "expected false_cancellation_sum_in_numerator to fire on (a + b)/b -> a");
        assertEquals("false_cancellation_sum_in_numerator", rule.orElseThrow().id());
        assertTrue(rule.orElseThrow().explanation().toLowerCase()
            .contains("gemeinsamer faktor"));
    }

    @Test
    void misconceptionRuleDetectsPartialSignDistribution() {
        // -(a + b) is parsed as 0 - (a + b); -a + b is parsed as (0 - a) + b.
        Optional<MisconceptionRule> rule = detector.detectTermStep("-(a + b)", "-a + b");
        assertTrue(rule.isPresent(),
            "expected sign_distribution_partial to fire on -(a + b) -> -a + b");
        assertEquals("sign_distribution_partial", rule.orElseThrow().id());
    }

    @Test
    void misconceptionRuleDetectsInequalityFlipMistake() {
        Optional<MisconceptionRule> rule = detector.detectInequalityStep(
            "-2*x < 4", "x < -2");
        assertTrue(rule.isPresent(),
            "expected inequality_missing_flip to fire on -2*x < 4 -> x < -2");
        assertEquals("inequality_missing_flip", rule.orElseThrow().id());
    }

    @Test
    void cleanCancellationIsNotFlaggedAsMisconception() {
        // (a * b) / b -> a is a *correct* cancellation, not the misconception.
        Optional<MisconceptionRule> rule = detector.detectTermStep("(a * b) / b", "a");
        assertTrue(rule.isEmpty(),
            "clean cancellation must not be flagged");
    }

    @Test
    void catalogueIsImmutableAndNonEmpty() {
        assertTrue(detector.catalogue().size() >= 3);
    }
}
