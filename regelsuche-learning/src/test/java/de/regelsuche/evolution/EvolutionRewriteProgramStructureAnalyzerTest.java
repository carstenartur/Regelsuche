package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Node;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramStructureAnalyzerTest {
    @Test
    void describesCompositionDecisionAndConservativePrimitiveDepth() {
        Node source = new Source("source_one", List.of("gene_one"));
        Node repeated = new Repeat("repeat_two", source, 2, 3);
        Node sequence = new Sequence(
            "sequence_three",
            List.of(source, repeated));
        Node decision = new FirstApplicable(
            "first_choice",
            List.of(sequence, source));

        var sourceFacts = EvolutionRewriteProgramStructureAnalyzer.analyze(source);
        assertEquals(1, sourceFacts.nodeCount());
        assertFalse(sourceFacts.containsCompositionTopology());
        assertFalse(sourceFacts.containsDecisionTopology());
        assertEquals(1, sourceFacts.minimumStructuralPrimitivePathSteps());

        var sequenceFacts = EvolutionRewriteProgramStructureAnalyzer.analyze(sequence);
        assertEquals(4, sequenceFacts.nodeCount());
        assertTrue(sequenceFacts.containsCompositionTopology());
        assertFalse(sequenceFacts.containsDecisionTopology());
        assertEquals(3, sequenceFacts.minimumStructuralPrimitivePathSteps());

        var decisionFacts = EvolutionRewriteProgramStructureAnalyzer.analyze(decision);
        assertEquals(6, decisionFacts.nodeCount());
        assertTrue(decisionFacts.containsCompositionTopology());
        assertTrue(decisionFacts.containsDecisionTopology());
        assertEquals(1, decisionFacts.minimumStructuralPrimitivePathSteps());
    }

    @Test
    void remainsExactlyEquivalentToTheExistingShowcaseAnalysis() {
        Node root = new FirstApplicable(
            "first_choice",
            List.of(
                new Sequence(
                    "composed_path",
                    List.of(
                        new Source("source_a", List.of("gene_a")),
                        new Repeat(
                            "repeat_b",
                            new Source("source_b", List.of("gene_b")),
                            2,
                            4))),
                new Repeat(
                    "repeat_c",
                    new Source("source_c", List.of("gene_c")),
                    3,
                    3)));

        var generic = EvolutionRewriteProgramStructureAnalyzer.analyze(root);
        var showcase = ProofCarryingShowcaseCandidateFreezer.analyze(root);

        assertEquals(showcase.nodeCount(), generic.nodeCount());
        assertEquals(
            showcase.containsCompositionTopology(),
            generic.containsCompositionTopology());
        assertEquals(
            showcase.containsDecisionTopology(),
            generic.containsDecisionTopology());
        assertEquals(
            showcase.minimumStructuralPrimitivePathSteps(),
            generic.minimumStructuralPrimitivePathSteps());
    }
}
