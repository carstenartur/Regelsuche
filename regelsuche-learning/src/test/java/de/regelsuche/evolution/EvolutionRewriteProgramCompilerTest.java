package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Choice;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.FirstApplicable;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prune;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Prioritize;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Priority;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Repeat;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Require;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Requirement;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Sequence;
import de.regelsuche.evolution.EvolutionRewriteProgramPlan.Source;
import de.regelsuche.transform.Transformation;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionRewriteProgramCompilerTest {
    @Test
    void compilesAndExecutesAComposedGenomeProgram() {
        EvolutionGenome genome = normalizationGenome();
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Prioritize(
                "cheap_first",
                new Require(
                    "bounded_steps",
                    new Sequence(
                        "normalizing_sequence",
                        List.of(
                            new Source("multiply_identity", List.of("mul_one")),
                            new Source("additive_identity", List.of("add_zero")))),
                    Requirement.maxPrimitiveSteps(2)),
                Priority.estimatedCostThenRule()),
            8,
            8);

        EvolutionRewriteProgramCompiler.CompiledRewriteProgram compiled =
            new EvolutionRewriteProgramCompiler().compile(genome, plan);
        Transformation result = compiled.engine().transform("(x * 1) + 0")
            .stream()
            .filter(candidate -> candidate.transformedExpression().equals("x"))
            .findFirst()
            .orElseThrow();

        assertTrue(result.rule().startsWith("program:"));
        assertEquals(List.of("mul_one", "add_zero"),
            compiled.referencedGeneIds());
        assertEquals(plan.contentHash(), compiled.planHash());
        assertEquals(genome.contentHash(), compiled.genomeHash());
        assertTrue(compiled.readableProgram().contains("sequence"));
        assertTrue(compiled.readableProgram().contains("MAX_PRIMITIVE_STEPS"));
    }

    @Test
    void alphaIdentityIgnoresNodeNamesButRetainsTopology() {
        EvolutionGenome genome = normalizationGenome();
        EvolutionRewriteProgramPlan first = EvolutionRewriteProgramPlan.create(
            genome,
            new Sequence(
                "first_root",
                List.of(
                    new Source("first_mul", List.of("mul_one")),
                    new Source("first_add", List.of("add_zero")))),
            6,
            6);
        EvolutionRewriteProgramPlan renamed = EvolutionRewriteProgramPlan.create(
            genome,
            new Sequence(
                "renamed_root",
                List.of(
                    new Source("renamed_mul", List.of("mul_one")),
                    new Source("renamed_add", List.of("add_zero")))),
            6,
            6);
        EvolutionRewriteProgramPlan alternativeTopology =
            EvolutionRewriteProgramPlan.create(
                genome,
                new FirstApplicable(
                    "alternative_root",
                    List.of(
                        new Source("alternative_mul", List.of("mul_one")),
                        new Source("alternative_add", List.of("add_zero")))),
                6,
                6);

        assertEquals(first.alphaStructuralHash(), renamed.alphaStructuralHash());
        assertNotEquals(first.contentHash(), renamed.contentHash());
        assertNotEquals(
            first.alphaStructuralHash(),
            alternativeTopology.alphaStructuralHash());
    }

    @Test
    void canonicalPlanBindsExactPayloadAndReadableTopology() {
        EvolutionGenome genome = normalizationGenome();
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Choice(
                "safe_alternatives",
                List.of(
                    new Source("zero_first", List.of("add_zero")),
                    new Repeat(
                        "bounded_multiply",
                        new Source("multiply_again", List.of("mul_one")),
                        1,
                        2))),
            8,
            8);

        assertTrue(plan.toCanonicalJson().contains(
            "\"schema\":\"regelsuche.evolution-rewrite-program-plan/v1\""));
        assertTrue(plan.toCanonicalJson().contains("\"nodeType\":\"CHOICE\""));
        assertTrue(plan.toReadableProgram().contains("choice"));
        assertTrue(plan.toReadableProgram().contains("repeat 1..2"));
        assertEquals(4, plan.nodeCount());
        assertEquals(3, plan.actualDepth());

        assertThrows(IllegalArgumentException.class,
            () -> new EvolutionRewriteProgramPlan(
                plan.schema(),
                plan.genomeHash(),
                plan.root(),
                plan.maxNodes(),
                plan.maxDepth(),
                plan.alphaStructuralHash(),
                EvolutionGenome.hash("tampered")));
    }

    @Test
    void rejectsUnsafeOrInconsistentPlansBeforeSearch() {
        EvolutionGenome genome = normalizationGenome();

        assertThrows(IllegalArgumentException.class,
            () -> EvolutionRewriteProgramPlan.create(
                genome,
                new Sequence(
                    "duplicate_node",
                    List.of(new Source(
                        "duplicate_node", List.of("add_zero")))),
                4,
                4));

        EvolutionRewriteProgramPlan unknownGene =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Source("unknown_source", List.of("missing_gene")),
                4,
                4);
        assertThrows(IllegalArgumentException.class,
            () -> new EvolutionRewriteProgramCompiler().compile(
                genome, unknownGene));

        EvolutionGenome otherGenome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("different_gene", "?A-0", "?A"));
        EvolutionRewriteProgramPlan boundToOther =
            EvolutionRewriteProgramPlan.create(
                otherGenome,
                new Source("different_source", List.of("different_gene")),
                4,
                4);
        assertThrows(IllegalArgumentException.class,
            () -> new EvolutionRewriteProgramCompiler().compile(
                genome, boundToOther));

        EvolutionRewriteProgramPlan excessiveRepeat =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Repeat(
                    "excessive_repeat",
                    new Source("repeat_source", List.of("add_zero")),
                    1,
                    33),
                4,
                4);
        assertThrows(IllegalArgumentException.class,
            () -> new EvolutionRewriteProgramCompiler().compile(
                genome, excessiveRepeat));

        EvolutionRewriteProgramPlan hiddenWidePrune =
            EvolutionRewriteProgramPlan.create(
                genome,
                new Prune(
                    "wide_prune",
                    new Source("pruned_source", List.of("add_zero")),
                    81,
                    "candidate limit"),
                4,
                4);
        assertThrows(IllegalArgumentException.class,
            () -> new EvolutionRewriteProgramCompiler().compile(
                genome, hiddenWidePrune));
    }

    @Test
    void preferredOrderingMustReferenceAnExecutedSource() {
        EvolutionGenome genome = normalizationGenome();

        assertThrows(IllegalArgumentException.class,
            () -> EvolutionRewriteProgramPlan.create(
                genome,
                new Prioritize(
                    "invalid_preference",
                    new Source("only_zero", List.of("add_zero")),
                    Priority.preferredGeneOrder(List.of("mul_one"))),
                4,
                4));
    }

    private static EvolutionGenome normalizationGenome() {
        return EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A"),
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
    }
}
