package de.regelsuche.math.algorithms.combinatorics;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Factor;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintCounter.Problem;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanLearning.Example;
import de.regelsuche.math.algorithms.combinatorics.FiniteConstraintPlanner.Strategy;
import de.regelsuche.math.algorithms.iteration.IterationLimits;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FiniteConstraintPlanLearningTest {
    private static final IterationLimits LIMITS = new IterationLimits(4_000_000, 200_000, 10_000);
    private static final long TRIAL = 700_000;

    @Test void costedCountingUsesFixedUnlessPriorTrialsCanRepayTraining() {
        var chain = problem(7, false, 1, Map.of());
        var model = FiniteConstraintPlanLearning.train(List.of(new Example("provenance", chain)),
            LIMITS, TRIAL, () -> false).model().orElseThrow();
        var hint = Optional.of(model);
        var fixed = FiniteConstraintPlanLearning.countWithPolicy(hint, chain, 1, TRIAL, LIMITS, () -> false);
        var learned = FiniteConstraintPlanLearning.countWithPolicy(hint, chain, 1000, TRIAL, LIMITS, () -> false);
        assertEquals("MIN_DEGREE", fixed.selectedProvider().orElseThrow());
        assertEquals("ASCENDING", learned.selectedProvider().orElseThrow());
        var expected = FiniteConstraintCounter.count(chain, LIMITS, () -> false);
        assertEquals(expected.count(), learned.value().orElseThrow().count());
        assertEquals(model.trainingWork(), learned.priorWork());
        var fallback = FiniteConstraintPlanLearning.countWithPolicy(hint, chain, 1000, 100, LIMITS, () -> false);
        assertEquals("MIN_DEGREE", fallback.selectedProvider().orElseThrow());
        assertEquals("WORK_LIMIT", fallback.attempts().getFirst().reason());
        assertTrue(fallback.attempts().getFirst().work() > 0);
        assertEquals(expected.work(), fallback.attempts().getLast().work());
        assertEquals(fallback.work(), fallback.preparationWork() + fallback.attempts().stream().mapToLong(a -> a.work()).sum());

        var cancelled = FiniteConstraintPlanLearning.countWithPolicy(hint, chain, 1000, TRIAL, LIMITS, () -> true);
        assertEquals("CANCELLED", cancelled.reason());
        assertTrue(cancelled.value().isEmpty());
        var unknown = FiniteConstraintPlanLearning.countWithPolicy(hint, problem(18, true, 1, Map.of()),
            1000, TRIAL, LIMITS, () -> false);
        assertEquals("MIN_DEGREE", unknown.selectedProvider().orElseThrow());
    }

    @Test void trainingOutcomesSelectDifferentHeuristicsOnUnseenConditionedAndWeightedSources() {
        var training = FiniteConstraintPlanLearning.train(List.of(
            new Example("chain-training", problem(7, false, 1, Map.of())),
            new Example("hub-training", problem(8, true, 1, Map.of()))), LIMITS, TRIAL, () -> false);
        var model = training.model().orElseThrow();
        assertEquals(training.work(), model.trainingWork());
        long trialWork = 0;
        for (var observation : training.observations()) {
            assertEquals(3, observation.trials().size());
            trialWork += observation.trials().stream().mapToLong(FiniteConstraintPlanLearning.Trial::work).sum();
        }
        assertTrue(training.work() > trialWork);
        assertEquals(Strategy.ASCENDING, model.observations().get(0).best().orElseThrow());
        assertEquals(Strategy.MIN_DEGREE, model.observations().get(1).best().orElseThrow());
        for (boolean hub : List.of(false, true)) {
            var unseen = problem(hub ? 9 : 8, hub, 3, Map.of(hub ? 8 : 7, false));
            var run = FiniteConstraintPlanLearning.count(model, unseen, LIMITS, () -> false);
            var decision = run.decision().orElseThrow();
            assertEquals(hub ? "hub-training" : "chain-training", decision.trainingSource());
            var fixed = FiniteConstraintCounter.count(unseen, decision.strategy(), LIMITS, () -> false);
            assertEquals(FiniteConstraintCounter.Status.EXACT, run.result().status());
            assertEquals(fixed.count(), run.result().count());
            assertEquals(fixed.certificate(), run.result().certificate());
            assertTrue(FiniteConstraintVerifier.verify(unseen, run.result().certificate().orElseThrow(), LIMITS, () -> false).valid());
            assertTrue(run.work() > fixed.work());
        }
    }

    @Test void unsuccessfulTrialsSpendWorkAndLimitsCannotProduceAnAnswerOrPartiallyTrainedPolicy() {
        var example = new Example("hub", problem(5, true, 1, Map.of()));
        var failed = FiniteConstraintPlanLearning.train(List.of(example), LIMITS, 12, () -> false);
        assertTrue(failed.model().isEmpty());
        assertEquals(3, failed.observations().getFirst().trials().size());
        assertTrue(failed.observations().getFirst().best().isEmpty());
        assertTrue(failed.work() > 0);
        assertTrue(FiniteConstraintPlanLearning.train(List.of(example), LIMITS, 100, () -> true).model().isEmpty());
        assertThrows(IllegalArgumentException.class,
            () -> FiniteConstraintPlanLearning.train(List.of(example, example), LIMITS, 50, () -> false));
        var trained = FiniteConstraintPlanLearning.train(List.of(example), LIMITS, TRIAL, () -> false);
        var run = FiniteConstraintPlanLearning.count(trained.model().orElseThrow(), example.problem(), LIMITS.withMaximumWork(8), () -> false);
        assertTrue(run.result().count().isEmpty());
        assertEquals("WORK_LIMIT", run.result().reason());
        assertTrue(run.work() <= 8);
        var exhausted = FiniteConstraintPlanLearning.train(List.of(example), LIMITS.withMaximumWork(500), 500, () -> false);
        assertTrue(exhausted.model().isEmpty());
        assertEquals("TRAINING_WORK_LIMIT", exhausted.reason());
    }

    @Test void chargedModelStorageAndFeaturesShareTheSameTermCapAsTheVerifiedCount() {
        var training = FiniteConstraintPlanLearning.train(List.of(new Example("chain", problem(7, false, 1, Map.of()))),
            LIMITS, TRIAL, () -> false);
        var model = training.model().orElseThrow();
        var evaluation = problem(8, false, 2, Map.of());
        var strategy = model.observations().getFirst().best().orElseThrow();
        int low = 1;
        int high = LIMITS.maximumTerms();
        while (low < high) {
            int mid = low + (high - low) / 2;
            var limits = new IterationLimits(TRIAL, mid, 10_000);
            if (FiniteConstraintCounter.count(evaluation, strategy, limits, () -> false).count().isPresent()) high = mid;
            else low = mid + 1;
        }
        var budget = new IterationLimits(TRIAL, low, 10_000);
        assertTrue(FiniteConstraintCounter.count(evaluation, strategy, budget, () -> false).count().isPresent());
        var result = FiniteConstraintPlanLearning.count(model, evaluation, budget, () -> false);
        assertTrue(result.decision().isPresent());
        assertEquals("TERM_LIMIT", result.result().reason());
        assertTrue(result.result().certificate().isEmpty());
    }

    private static Problem problem(int variables, boolean hub, int weight, Map<Integer, Boolean> fixed) {
        var factors = new ArrayList<Factor>();
        for (int v = 1; v < variables; v++)
            factors.add(new Factor(List.of(hub ? 0 : v - 1, v),
                List.of(BigInteger.ONE, BigInteger.valueOf(weight), BigInteger.ONE, BigInteger.ZERO)));
        return new Problem(variables, factors, fixed);
    }
}
