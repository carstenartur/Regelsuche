package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Input;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.Profile;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkSearchReplay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class TraceStrategyDispatchLearnerTest {
    private static TraceStrategyDispatchLearner.FrozenPolicy policy;
    private static final TraceStrategyDispatchLearner LEARNER = new TraceStrategyDispatchLearner();

    @BeforeAll static void train() { policy = TraceStrategyDispatchExample.train(); }

    @Test
    void routesAreChosenByCompleteMeasuredTrainingTrialsAndRejectedTrialsArePaid() {
        assertFalse(policy.routes().isEmpty());
        assertTrue(policy.trials().stream().anyMatch(trial -> !trial.accepted() && !trial.routes().isEmpty()));
        long previous = policy.trials().getFirst().measuredWork();
        var baseline = policy.trials().getFirst().observations();
        for (var trial : policy.trials()) if (trial.accepted()) {
            assertTrue(trial.measuredWork() < previous);
            previous = trial.measuredWork();
            for (int i = 0; i < baseline.size(); i++) assertTrue(trial.observations().get(i).search().bestState().score().weightedTotal()
                <= baseline.get(i).search().bestState().score().weightedTotal());
        }
        assertEquals(policy.contextCollectionWork() + policy.trials().stream().mapToLong(TraceStrategyDispatchLearner.Trial::measuredWork).sum(),
            policy.dispatchLearningWork());
        assertEquals(policy.dispatchLearningWork() + policy.formationWork(), policy.learningWork());
    }

    @Test
    void freezeIsDeterministicAndNoUsefulTrainingLeavesTheFlatPolicy() {
        var reversed = new ArrayList<>(TraceStrategyDispatchExample.selectionInputs());
        Collections.reverse(reversed);
        assertEquals(policy.toCanonicalJson(), LEARNER.train(policy.formation(), reversed, policy.limits()).toCanonicalJson());
        var plain = LEARNER.train(policy.formation(), List.of(new Input("one", "a+51"), new Input("two", "b+53")), policy.limits());
        assertTrue(plain.routes().isEmpty());
        var input = new Input("fresh", "c+59");
        assertEquals(LEARNER.apply(plain, input, Profile.FLAT_GREEDY).search(), LEARNER.apply(plain, input, Profile.LEARNED_DISPATCH).search());
        assertThrows(UnsupportedOperationException.class, () -> policy.routes().clear());
    }

    @Test
    void semanticFormationAndSelectionLeakageCannotEnterApplications() {
        for (var expression : List.of("3+x*x", "(z+7)*(z-7)+72", "7*q^2", "(u*v)^2+13")) {
            for (var profile : Profile.values()) assertThrows(IllegalArgumentException.class,
                () -> LEARNER.apply(policy, new Input("leak", expression), profile));
        }
        assertThrows(IllegalArgumentException.class, () -> LEARNER.train(policy.formation(),
            List.of(new Input("one", "x*x+11"), new Input("two", "11+y^2")), policy.limits()));
        for (var expression : List.of("sin(x)+1", "a+b+c+d+e", "(x^32)^32")) assertThrows(IllegalArgumentException.class,
            () -> LEARNER.apply(policy, new Input("unsupported", expression), Profile.LEARNED_DISPATCH));
    }

    @Test
    void misleadingContextFallsBackAndThePrimitivePathBudgetRemainsEffective() {
        var near = new Input("near", "(x+y)*(x-y)+z*z+101");
        var actual = LEARNER.apply(policy, near, Profile.LEARNED_DISPATCH);
        var baseline = LEARNER.apply(policy, near, Profile.FLAT_GREEDY);
        assertEquals(baseline.search().bestState().expression(), actual.search().bestState().expression());
        assertTrue(actual.measuredWork() > baseline.measuredWork());
        assertFalse(actual.search().bestState().programUsed());
        new ExactPolynomialAnalysis().requireEquivalent(near.expression(), actual.search().bestState().expression());

        String source = "((x+y)*(x-y)+y*y)+101";
        var problem = Problem.withoutTarget(source, new SearchExpansionSource.Measured(
            TraceStrategyDispatchLearner.engine(policy, Profile.LEARNED_DISPATCH)), new ExpressionScorer(),
            new ExpressionCanonicalizer(), Budget.primitive(1, 80, 32, 1, 30_000));
        var result = new WorkBudgetBestFirstSearchStrategy().search(problem);
        assertEquals(1, result.bestState().primitiveDepth());
        assertFalse(result.bestState().programUsed());
        assertNotEquals("x ^ 2 + 101", result.bestState().expression());
        assertEquals(result, WorkSearchReplay.verify(result.toCanonicalJson(), problem));
    }
}
