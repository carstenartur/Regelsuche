package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.EvolutionGenome.FeatureWeight;
import de.regelsuche.evolution.EvolutionGenome.FitnessSignal;
import de.regelsuche.evolution.EvolutionGenome.GuardPolicy;
import de.regelsuche.evolution.EvolutionGenome.Objective;
import de.regelsuche.evolution.EvolutionGenome.ResourceBudget;
import de.regelsuche.evolution.EvolutionGenomeValidator.BlockerCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvolutionGenomeValidatorTest {
    private final EvolutionGenomeValidator validator = new EvolutionGenomeValidator();

    @Test
    void acceptsBoundedExecutableSeedGenome() {
        var report = validator.validate(EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A")));

        assertTrue(report.accepted(), report.blockers().toString());
        assertTrue(report.toCanonicalJson().contains("\"status\":\"ACCEPTED\""));
    }

    @Test
    void rejectsUnboundTargetPlaceholderBeforeEvaluation() {
        var genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("unsafe_target", "?A+0", "?B"));

        var report = validator.validate(genome);

        assertFalse(report.accepted());
        assertTrue(report.blockerCodes().contains(
            BlockerCode.UNBOUND_TARGET_PLACEHOLDER));
    }

    @Test
    void rejectsDirectCyclesAndAlphaDuplicateRules() {
        var genome = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.gene("forward_rule", "?A+0", "?A"),
            EvolutionGenomeTestFixtures.gene("reverse_rule", "?B", "?B+0"),
            EvolutionGenomeTestFixtures.gene("duplicate_rule", "?X+0", "?X"));

        var report = validator.validate(genome);

        assertFalse(report.accepted());
        assertTrue(report.blockerCodes().contains(BlockerCode.REWRITE_CYCLE));
        assertTrue(report.blockerCodes().contains(
            BlockerCode.DUPLICATE_STRUCTURAL_REWRITE));
    }

    @Test
    void rejectsTargetSignalsInOpenTargetGenome() {
        EvolutionGenome seed = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionGenome genome = EvolutionGenome.create(
            Objective.OPEN_TARGET_OPERATOR,
            seed.trainingScope(),
            seed.rewrites(),
            List.of(new FeatureWeight(FitnessSignal.DECLARED_TARGET_DISTANCE, 500)),
            seed.guardPolicy(),
            seed.budget(),
            seed.requiredCapabilities(),
            List.of());

        var report = validator.validate(genome);

        assertFalse(report.accepted());
        assertTrue(report.blockerCodes().contains(
            BlockerCode.TARGET_SIGNAL_IN_OPEN_TARGET_GENOME));
    }

    @Test
    void rejectsAnyAttemptToDisableSafetyOrResourceGuards() {
        EvolutionGenome seed = EvolutionGenomeTestFixtures.genome(
            EvolutionGenomeTestFixtures.addZero("add_zero", "A"));
        EvolutionGenome genome = EvolutionGenome.create(
            seed.objective(),
            seed.trainingScope(),
            seed.rewrites(),
            seed.rankingFeatures(),
            new GuardPolicy(true, true, false, true, false),
            new ResourceBudget(16, 128, 12, 32, 80),
            seed.requiredCapabilities(),
            List.of());

        var report = validator.validate(genome);

        assertFalse(report.accepted());
        assertTrue(report.blockerCodes().contains(
            BlockerCode.APPLICABILITY_GUARD_DISABLED));
        assertTrue(report.blockerCodes().contains(
            BlockerCode.NONDETERMINISTIC_TIE_BREAKING));
    }
}
