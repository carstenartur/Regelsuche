package de.regelsuche.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.transform.RewriteKind;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MacroRuleMinerTest {

    @Test
    void detectsRepeatedMacroRuleSequence() {
        DiscoveredTransformation t1 = transformation("t1", List.of(
            step(0, "a", "b", "expand"),
            step(1, "b", "c", "combine"),
            step(2, "c", "d", "simplify")
        ));
        DiscoveredTransformation t2 = transformation("t2", List.of(
            step(0, "x", "y", "expand"),
            step(1, "y", "z", "combine")
        ));

        List<MacroRuleCandidate> macros = new MacroRuleMiner().mine(List.of(t1, t2));

        assertFalse(macros.isEmpty());
        boolean foundSequence = macros.stream()
            .anyMatch(m -> m.ruleIdSequence().equals(List.of("expand", "combine")) && m.occurrences() >= 2);
        assertTrue(foundSequence, "Expected to find macro [expand, combine] with occurrences >= 2");
        MacroRuleCandidate macro = macros.stream()
            .filter(m -> m.ruleIdSequence().equals(List.of("expand", "combine")))
            .findFirst().orElseThrow();
        assertEquals(2.0, macro.compressionRatio(), 1e-9);
        assertTrue(macro.supportingTransformationIds().containsAll(List.of("t1", "t2")));
    }

    @Test
    void respectsMinOccurrencesThreshold() {
        DiscoveredTransformation t1 = transformation("t1", List.of(
            step(0, "a", "b", "rule1"),
            step(1, "b", "c", "rule2")
        ));
        // No second transformation contains this sequence
        List<MacroRuleCandidate> macros = new MacroRuleMiner(2, 2, 4).mine(List.of(t1));
        assertTrue(macros.isEmpty(), "Single transformation should not yield macros at minOccurrences=2");
    }

    private static DiscoveredTransformation transformation(String id, List<TransformationStep> steps) {
        ExpressionScore origin = new ExpressionScore(10, 10, 5, 1, 0);
        ExpressionScore improved = new ExpressionScore(5, 5, 2, 1, 0);
        return new DiscoveredTransformation(
            id,
            steps.getFirst().beforeExpression(),
            steps.getLast().afterExpression(),
            steps,
            origin,
            improved,
            origin.improvementTo(improved),
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            "hash-" + id
        );
    }

    private static TransformationStep step(int index, String from, String to, String rule) {
        return new TransformationStep(index, from, to, rule, RewriteKind.SIMPLIFY, 10, 8, true, "");
    }
}
