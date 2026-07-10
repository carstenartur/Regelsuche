package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DynamicCandidateRegistryTest {

    private final DynamicOperatorCompiler compiler = new DynamicOperatorCompiler();
    private final DynamicCandidateRegistry registry = new DynamicCandidateRegistry();

    @Test
    void registeredOperatorStartsInCandidateState() {
        DynamicPatternOperator op = compile("h1", "A * B + A * C", "A * (B + C)");

        registry.register(op);

        DynamicCandidateRegistry.RegistryEntry entry = registry.entry(op.ruleId()).orElseThrow();
        assertEquals(DynamicCandidateRegistry.CandidateStatus.CANDIDATE, entry.status());
    }

    @Test
    void candidateIsNotReturnedByValidatedOperators() {
        DynamicPatternOperator op = compile("h2", "A + B", "B + A");
        registry.register(op);

        assertTrue(registry.validatedOperators().isEmpty(),
            "CANDIDATE operators must not appear in validated list");
        assertEquals(1, registry.candidateOperators().size());
    }

    @Test
    void promotionTransitionsToCandidateToValidated() {
        DynamicPatternOperator op = compile("h3", "A * B + A * C", "A * (B + C)");
        registry.register(op);

        registry.promote(op.ruleId());

        DynamicCandidateRegistry.RegistryEntry entry = registry.entry(op.ruleId()).orElseThrow();
        assertEquals(DynamicCandidateRegistry.CandidateStatus.VALIDATED, entry.status());
        assertFalse(registry.validatedOperators().isEmpty());
        assertTrue(registry.candidateOperators().isEmpty());
    }

    @Test
    void blockingTransitionsToBlocked() {
        DynamicPatternOperator op = compile("h4", "A + B", "B + A");
        registry.register(op);

        registry.block(op.ruleId(), "counterexample found");

        DynamicCandidateRegistry.RegistryEntry entry = registry.entry(op.ruleId()).orElseThrow();
        assertEquals(DynamicCandidateRegistry.CandidateStatus.BLOCKED, entry.status());
        assertEquals("counterexample found", entry.blockReason());
    }

    @Test
    void validatedOperatorCanBeBlocked() {
        DynamicPatternOperator op = compile("h5", "A * B + A * C", "A * (B + C)");
        registry.register(op);
        registry.promote(op.ruleId());

        registry.block(op.ruleId(), "failed assumption");

        DynamicCandidateRegistry.RegistryEntry entry = registry.entry(op.ruleId()).orElseThrow();
        assertEquals(DynamicCandidateRegistry.CandidateStatus.BLOCKED, entry.status());
        assertTrue(registry.validatedOperators().isEmpty(),
            "Blocked operator must not appear in validated list");
    }

    @Test
    void duplicateRegistrationThrows() {
        DynamicPatternOperator op = compile("h6", "A + B", "B + A");
        registry.register(op);

        assertThrows(IllegalStateException.class, () -> registry.register(op),
            "Duplicate registration must be rejected");
    }

    @Test
    void promotingAlreadyValidatedOperatorThrows() {
        DynamicPatternOperator op = compile("h7", "A + B", "B + A");
        registry.register(op);
        registry.promote(op.ruleId());

        assertThrows(IllegalStateException.class, () -> registry.promote(op.ruleId()),
            "Promoting an already-VALIDATED operator must throw");
    }

    @Test
    void promotingBlockedOperatorThrows() {
        DynamicPatternOperator op = compile("h8", "A + B", "B + A");
        registry.register(op);
        registry.block(op.ruleId(), "test");

        assertThrows(IllegalStateException.class, () -> registry.promote(op.ruleId()),
            "Promoting a BLOCKED operator must throw");
    }

    @Test
    void blockingAlreadyBlockedOperatorThrows() {
        DynamicPatternOperator op = compile("h9", "A + B", "B + A");
        registry.register(op);
        registry.block(op.ruleId(), "first block");

        assertThrows(IllegalStateException.class, () -> registry.block(op.ruleId(), "second block"),
            "Blocking an already-BLOCKED operator must throw");
    }

    @Test
    void historyRecordsAllTransitions() {
        DynamicPatternOperator op = compile("h10", "A * B + A * C", "A * (B + C)");
        registry.register(op);
        registry.promote(op.ruleId());
        registry.block(op.ruleId(), "ablation failed");

        var history = registry.history();

        assertEquals(3, history.size(), "Expected register + promote + block = 3 transitions");
        assertEquals(DynamicCandidateRegistry.CandidateStatus.CANDIDATE, history.get(0).toStatus());
        assertEquals(DynamicCandidateRegistry.CandidateStatus.VALIDATED, history.get(1).toStatus());
        assertEquals(DynamicCandidateRegistry.CandidateStatus.BLOCKED, history.get(2).toStatus());
    }

    @Test
    void operatorCanBeRetrievedByRuleId() {
        DynamicPatternOperator op = compile("h11", "A + B", "B + A");
        registry.register(op);

        assertTrue(registry.operator(op.ruleId()).isPresent());
        assertFalse(registry.operator("nonexistent").isPresent());
    }

    @Test
    void isRegisteredReflectsState() {
        DynamicPatternOperator op = compile("h12", "A + B", "B + A");

        assertFalse(registry.isRegistered(op.ruleId()));
        registry.register(op);
        assertTrue(registry.isRegistered(op.ruleId()));
    }

    @Test
    void endToEndFromMiningToValidatedOperator() {
        // Simulate the full path: mine → compile → register → promote
        DynamicPatternOperator op = compile(
            "binomial-expansion-v1",
            "(A + B)^2",
            "A^2 + 2 * A * B + B^2"
        );

        registry.register(op);
        assertEquals(DynamicCandidateRegistry.CandidateStatus.CANDIDATE,
            registry.entry(op.ruleId()).orElseThrow().status());

        // Positive holdout passes (simulated)
        registry.promote(op.ruleId());
        assertEquals(DynamicCandidateRegistry.CandidateStatus.VALIDATED,
            registry.entry(op.ruleId()).orElseThrow().status());

        // Operator is now available for activation
        assertEquals(1, registry.validatedOperators().size());
        assertEquals(op.ruleId(), registry.validatedOperators().getFirst().ruleId());

        // Firing on a concrete expression
        var candidates = op.generateCandidates("(x + 1)^2");
        assertFalse(candidates.isEmpty(),
            "Validated dynamic operator must fire on a matching expression");
    }

    private DynamicPatternOperator compile(String id, String left, String right) {
        var result = compiler.compile(id, "v1", left, right);
        assertTrue(result.isSuccess(), "Compilation failed: " + result.rejectionReason());
        return result.operator().orElseThrow();
    }
}
