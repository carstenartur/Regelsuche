package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvolutionRewriteProgramPlanCodecTest {
    @Test
    void roundTripsEverySupportedProgramNode(@TempDir Path tempDir) {
        EvolutionGenome genome = genome();
        EvolutionRewriteProgramPlan plan = EvolutionRewriteProgramPlan.create(
            genome,
            new Prune(
                "bounded_result",
                new Prioritize(
                    "preferred_rules",
                    new Require(
                        "step_guard",
                        new Repeat(
                            "bounded_repeat",
                            new Choice(
                                "all_alternatives",
                                List.of(
                                    new Sequence(
                                        "normalization_sequence",
                                        List.of(
                                            new Source(
                                                "multiply_source",
                                                List.of("mul_one")),
                                            new Source(
                                                "add_source",
                                                List.of("add_zero")))),
                                    new FirstApplicable(
                                        "fallback_sequence",
                                        List.of(
                                            new Source(
                                                "fallback_add",
                                                List.of("add_zero")),
                                            new Source(
                                                "fallback_multiply",
                                                List.of("mul_one")))))),
                            1,
                            2),
                        Requirement.maxPrimitiveSteps(4)),
                    Priority.preferredGeneOrder(
                        List.of("mul_one", "add_zero"))),
                12,
                "frozen candidate budget"),
            16,
            16);
        EvolutionRewriteProgramPlanCodec codec =
            new EvolutionRewriteProgramPlanCodec();

        String json = codec.write(plan);
        EvolutionRewriteProgramPlan decoded = codec.read(json);
        Path output = codec.write(tempDir.resolve("plan.json"), plan);

        assertEquals(plan, decoded);
        assertEquals(plan, codec.read(output));
        assertEquals(json, codec.write(decoded));
        assertTrue(json.contains("\"nodeType\":\"FIRST_APPLICABLE\""));
        assertTrue(json.contains("\"kind\":\"PREFERRED_GENE_ORDER\""));
    }

    @Test
    void rejectsUnknownDuplicateAndTrailingContent() {
        EvolutionRewriteProgramPlanCodec codec =
            new EvolutionRewriteProgramPlanCodec();
        String json = codec.write(EvolutionRewriteProgramPlan.create(
            genome(),
            new Source("single_source", List.of("add_zero")),
            4,
            4));

        String unknownTopLevel = json.replace(
            "\"contentHash\":",
            "\"unexpected\":true,\"contentHash\":");
        String unknownNodeField = json.replace(
            "\"geneIds\":",
            "\"unexpectedNodeField\":true,\"geneIds\":");
        String duplicateSchema = json.replaceFirst(
            "\\{\\\"schema\\\":",
            "{\"schema\":\"duplicate\",\"schema\":");

        assertThrows(IllegalArgumentException.class,
            () -> codec.read(unknownTopLevel));
        assertThrows(IllegalArgumentException.class,
            () -> codec.read(unknownNodeField));
        assertThrows(IllegalArgumentException.class,
            () -> codec.read(duplicateSchema));
        assertThrows(IllegalArgumentException.class,
            () -> codec.read(json + "{}"));
        assertThrows(IllegalArgumentException.class,
            () -> codec.read(" "));
    }

    @Test
    void rejectsNodeTypeFieldSubstitutionEvenWhenHashIsPresent() {
        EvolutionRewriteProgramPlanCodec codec =
            new EvolutionRewriteProgramPlanCodec();
        String json = codec.write(EvolutionRewriteProgramPlan.create(
            genome(),
            new Source("single_source", List.of("add_zero")),
            4,
            4));
        String substituted = json.replace(
            "\"nodeType\":\"SOURCE\"",
            "\"nodeType\":\"UNBOUNDED_LOOP\"");

        assertThrows(IllegalArgumentException.class,
            () -> codec.read(substituted));
    }

    private static EvolutionGenome genome() {
        return EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("mul_one", "?A*1", "?A"),
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
    }
}
