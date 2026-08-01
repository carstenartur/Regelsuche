package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramAlphaIdentityTest {
    @Test
    void ignoresNodeNamesButRetainsOrderedRuleSemantics() {
        EvolutionGenome genome = genome();
        EvolutionRewriteProgramPlan original = plan(
            genome,
            new FirstApplicable(
                "original_fallback",
                List.of(
                    new Source("original_zero", List.of("add_zero")),
                    new Source("original_one", List.of("mul_one")))));
        EvolutionRewriteProgramPlan renamed = plan(
            genome,
            new FirstApplicable(
                "renamed_fallback",
                List.of(
                    new Source("renamed_zero", List.of("add_zero")),
                    new Source("renamed_one", List.of("mul_one")))));
        EvolutionRewriteProgramPlan reordered = plan(
            genome,
            new FirstApplicable(
                "reordered_fallback",
                List.of(
                    new Source("reordered_one", List.of("mul_one")),
                    new Source("reordered_zero", List.of("add_zero")))));

        assertEquals(
            original.alphaStructuralHash(),
            renamed.alphaStructuralHash(),
            "node identifiers are non-semantic alpha names");
        assertNotEquals(
            original.alphaStructuralHash(),
            reordered.alphaStructuralHash(),
            "first-applicable order changes executable fallback semantics");
    }

    @Test
    void sequenceOrderOfDistinctRulesChangesAlphaIdentity() {
        EvolutionGenome genome = genome();
        EvolutionRewriteProgramPlan normalizeThenSimplify = plan(
            genome,
            new Sequence(
                "normalize_then_simplify",
                List.of(
                    new Source("multiply_first", List.of("mul_one")),
                    new Source("add_second", List.of("add_zero")))));
        EvolutionRewriteProgramPlan simplifyThenNormalize = plan(
            genome,
            new Sequence(
                "simplify_then_normalize",
                List.of(
                    new Source("add_first", List.of("add_zero")),
                    new Source("multiply_second", List.of("mul_one")))));

        assertNotEquals(
            normalizeThenSimplify.alphaStructuralHash(),
            simplifyThenNormalize.alphaStructuralHash(),
            "sequence order is part of the learned program structure");
    }

    private static EvolutionRewriteProgramPlan plan(
        EvolutionGenome genome,
        EvolutionRewriteProgramPlan.Node root
    ) {
        return EvolutionRewriteProgramPlan.create(genome, root, 8, 8);
    }

    private static EvolutionGenome genome() {
        return EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A"),
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
    }
}
