package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionGenomeTest {
    @Test
    void canonicalPayloadIsStableAcrossInputOrdering() {
        var first = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            EvolutionGenomeTestFixtures.scope('a'),
            List.of(
                EvolutionGenomeTestFixtures.gene("z_rule", "?A*1", "?A"),
                EvolutionGenomeTestFixtures.addZero("a_rule", "A")),
            List.of(
                new FeatureWeight(FitnessSignal.RUNTIME_COST, -200),
                new FeatureWeight(FitnessSignal.STRUCTURAL_NOVELTY, 800)),
            GuardPolicy.strictDefault(),
            ResourceBudget.conservativeDefault(),
            List.of("solver.smt", "core.ast-rewrite"),
            List.of(EvolutionGenomeTestFixtures.hash('f')));
        var second = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            EvolutionGenomeTestFixtures.scope('a'),
            List.of(
                EvolutionGenomeTestFixtures.addZero("a_rule", "A"),
                EvolutionGenomeTestFixtures.gene("z_rule", "?A*1", "?A")),
            List.of(
                new FeatureWeight(FitnessSignal.STRUCTURAL_NOVELTY, 800),
                new FeatureWeight(FitnessSignal.RUNTIME_COST, -200)),
            GuardPolicy.strictDefault(),
            ResourceBudget.conservativeDefault(),
            List.of("core.ast-rewrite", "solver.smt"),
            List.of(EvolutionGenomeTestFixtures.hash('f')));

        assertEquals(first.contentHash(), second.contentHash());
        assertEquals(first.toCanonicalJson(), second.toCanonicalJson());
        assertTrue(first.toCanonicalJson().startsWith(
            "{\"schema\":\"regelsuche.evolution-genome/v1\""));
    }

    @Test
    void alphaStructuralIdentityIgnoresPlaceholderAndGeneNames() {
        EvolutionGenome first = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionGenome renamed = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("renamed_rule", "X"));

        assertEquals(first.alphaStructuralHash(), renamed.alphaStructuralHash());
        assertNotEquals(first.contentHash(), renamed.contentHash());
    }

    @Test
    void structuralIdentityIsReusableAcrossTrainProvenance() {
        EvolutionGenome first = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionGenome second = EvolutionGenome.create(
            first.objective(),
            EvolutionGenomeTestFixtures.scope('c'),
            first.rewrites(),
            first.rankingFeatures(),
            first.guardPolicy(),
            first.budget(),
            first.requiredCapabilities(),
            List.of(first.contentHash()));

        assertEquals(first.alphaStructuralHash(), second.alphaStructuralHash());
        assertNotEquals(first.contentHash(), second.contentHash());
    }
    @Test
    void strictCodecRoundTripsCanonicalGenomeForReplay() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionGenomeCodec codec = new EvolutionGenomeCodec();

        EvolutionGenome replayed = codec.read(codec.write(genome));

        assertEquals(genome, replayed);
        assertEquals(genome.toCanonicalJson(), replayed.toCanonicalJson());
    }

    @Test
    void codecRejectsUnknownFieldsAndTamperedPayloads() {
        EvolutionGenome genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionGenomeCodec codec = new EvolutionGenomeCodec();
        String canonical = codec.write(genome);

        String unknown = canonical.replaceFirst("\\{", "{\"unknown\":true,");
        String tampered = canonical.replace(
            "\"sourcePattern\":\"?A+0\"",
            "\"sourcePattern\":\"?A+1\"");

        assertThrows(IllegalArgumentException.class, () -> codec.read(unknown));
        assertThrows(IllegalArgumentException.class, () -> codec.read(tampered));
    }

}
