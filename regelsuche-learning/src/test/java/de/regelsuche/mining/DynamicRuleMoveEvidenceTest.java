package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.search.moves.MoveContext;
import de.regelsuche.search.moves.MoveState;
import de.regelsuche.search.moves.SearchMove;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicRuleMoveEvidenceTest {
    @Test
    void compilingAnInventoryRulePreservesUtilityAssumptionsAndQuarantine() {
        var rule = new ReusableRule("learned-factor", "A * B + A * C", "A * (B + C)", List.of(),
            CandidateProofStatus.OBSERVED, RuleStatus.NEW, 4, 20, Instant.EPOCH)
            .withLearningProgress(5, 20, List.of("train-path"), 0.9).withAssumptions(List.of("x != 0"));
        var compiled = new DynamicOperatorCompiler().compile(rule).operator().orElseThrow();
        assertEquals(rule, compiled.reusableRuleEvidence().orElseThrow());
        var state = MoveState.root("x * y + x * z");
        assertTrue(compiled.candidates(state, MoveContext.frozen("x * (y + z)")).moves().isEmpty());
        var batch = compiled.candidates(state, new MoveContext("x * (y + z)", List.of("x != 0"), MoveContext.Phase.TRAIN));
        assertFalse(batch.moves().isEmpty());
        var move = batch.moves().getFirst();
        assertEquals(rule.id(), move.ruleId());
        assertEquals(20, move.valueEvidence().legacyAverageImprovement());
        assertEquals(0.9, move.valueEvidence().confidence());
        assertEquals(-1, move.valueEvidence().bestKnownPrimitiveSteps(), "a large average improvement is not a primitive distance");
        assertEquals(0, move.valueEvidence().knownDepthCompression());
        assertEquals(rule.assumptions(), move.assumptions());
        assertEquals(SearchMove.ProofStrength.EMPIRICAL, move.proofStrength());
        assertTrue(move.primitiveExpansion().isEmpty(), "supporting path references are not an instantiated primitive proof");
        var plain = new DynamicOperatorCompiler().compile("plain", "v1", "A * B + A * C", "A * (B + C)").operator().orElseThrow();
        assertTrue(plain.reusableRuleEvidence().isEmpty());
        assertEquals(SearchMove.SourceKind.HYPOTHESIS, plain.descriptor().sourceKind());
        assertThrows(IllegalArgumentException.class, () -> plain.withRuleEvidence(rule));
    }
}
