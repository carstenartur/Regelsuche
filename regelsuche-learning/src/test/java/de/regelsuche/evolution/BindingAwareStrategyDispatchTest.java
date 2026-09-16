package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;
import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Input;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.BindingLimits;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.Profile;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkSearchReplay;
import de.regelsuche.transform.TransformationProvenance;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class BindingAwareStrategyDispatchTest {
    private static final TraceStrategyDispatchLearner LEARNER = new TraceStrategyDispatchLearner();
    private static final BindingLimits LIMITS = new BindingLimits(32, 100_000, 20_000);
    private static TraceStrategyDispatchLearner.FrozenPolicy historical;
    private static TraceStrategyDispatchLearner.FrozenPolicy bound;

    @BeforeAll static void train() {
        historical = TraceStrategyDispatchExample.train();
        bound = LEARNER.trainBindingAware(historical.formation(), TraceStrategyDispatchExample.selectionInputs(),
            historical.limits(), LIMITS);
    }

    @Test void historicalPolicyIdentityAndProfilesRemainUnchanged() {
        assertEquals("sha256:170363d5facca1ff53615fa8197bf70df268a9d872e0915eb5f228c42cddaf43", historical.contentHash());
        assertEquals(4, Profile.values().length);
        assertFalse(historical.bindingAware());
        assertEquals(0, historical.bindingFormationWork());
    }

    @Test void theNewVersionContainsTrainDerivedTemplatesAndBoundedMatchingIdentity() {
        assertTrue(bound.bindingAware());
        assertTrue(bound.bindingTemplateCount() > 0);
        assertTrue(bound.bindingFormationWork() > 0);
        assertTrue(bound.toCanonicalJson().contains("regelsuche.trace-strategy-dispatch/v2"));
        assertTrue(bound.toCanonicalJson().contains("regelsuche.trace-binding-model/v2"));
        assertNotEquals(historical.contentHash(), bound.contentHash());
        var altered = LEARNER.trainBindingAware(historical.formation(), TraceStrategyDispatchExample.selectionInputs(),
            historical.limits(), new BindingLimits(32, 100_000, 20_001));
        assertNotEquals(bound.contentHash(), altered.contentHash());
    }

    @Test void formationAndEveryRejectedTrialAreChargedWithoutWeakeningUtilitySelection() {
        assertEquals(bound.bindingFormationWork() + bound.contextCollectionWork()
            + bound.trials().stream().mapToLong(TraceStrategyDispatchLearner.Trial::measuredWork).sum(),
            bound.dispatchLearningWork());
        assertEquals(historical.trials().getFirst(), bound.trials().getFirst());
        assertTrue(bound.trials().size() > 1);
        long previous = bound.trials().getFirst().measuredWork();
        for (var trial : bound.trials()) if (trial.accepted()) {
            assertTrue(trial.measuredWork() < previous);
            previous = trial.measuredWork();
            for (int i = 0; i < trial.observations().size(); i++) assertTrue(
                trial.observations().get(i).search().bestState().score().weightedTotal()
                    <= bound.trials().getFirst().observations().get(i).search().bestState().score().weightedTotal());
        }
    }

    @Test void aBoundContinuationRunsThroughTheActualEngineAndRetainsPrimitiveReplay() {
        String source = "((x+y)*(x-y)+y*y)+101";
        var engine = TraceStrategyDispatchLearner.engine(bound, Profile.UNGATED_CONTINUATIONS);
        var batch = engine.transformMeasured(source);
        var step = batch.transformations().getFirst();
        assertEquals(3, step.primitiveStepCount());
        assertInstanceOf(TransformationProvenance.Sequence.class, step.provenance());
        assertTrue(step.rule().contains("bound-resumption"));
        assertTrue(batch.workMetrics().delegatedMechanicalWorkUnits() > 0);
        assertTrue(batch.workMetrics().delegatedMechanicalWorkUnits() <= LIMITS.maximumMatchingWorkPerExpansion());
        new ExactPolynomialAnalysis().requireEquivalent(source, step.transformedExpression());
        var problem = Problem.withoutTarget(source, new SearchExpansionSource.Measured(engine),
            new ExpressionScorer(), new ExpressionCanonicalizer(), historical.limits().budget());
        var result = new WorkBudgetBestFirstSearchStrategy().search(problem);
        assertTrue(result.bestState().programUsed());
        assertEquals(result, WorkSearchReplay.verify(result.toCanonicalJson(), problem));
        assertEquals(3, new TraceRewriteStrategyLearner().audit(source, List.of(step)));
    }

    @Test void mismatchedResidualAndSharedBudgetCutoffUseThePrimitiveFallback() {
        var engine = TraceStrategyDispatchLearner.engine(bound, Profile.UNGATED_CONTINUATIONS);
        var mismatch = engine.transformMeasured("(x+y)*(x-y)+z*z+103");
        assertEquals(1, mismatch.transformations().getFirst().primitiveStepCount());
        assertTrue(mismatch.workMetrics().delegatedMechanicalWorkUnits() > 0);
        var limited = LEARNER.trainBindingAware(historical.formation(), TraceStrategyDispatchExample.selectionInputs(),
            historical.limits(), new BindingLimits(32, 100_000, 1));
        var cut = TraceStrategyDispatchLearner.engine(limited, Profile.UNGATED_CONTINUATIONS)
            .transformMeasured("((x+y)*(x-y)+y*y)+107");
        assertEquals(1, cut.transformations().getFirst().primitiveStepCount());
        assertEquals(1, cut.workMetrics().delegatedMechanicalWorkUnits());
    }

    @Test void trainingOrderIsDeterministicAndApplicationCannotFeedBackIntoTheFreeze() {
        var reversed = new ArrayList<>(TraceStrategyDispatchExample.selectionInputs());
        Collections.reverse(reversed);
        assertEquals(bound.toCanonicalJson(), LEARNER.trainBindingAware(historical.formation(), reversed,
            historical.limits(), LIMITS).toCanonicalJson());
        String hash = bound.contentHash();
        LEARNER.apply(bound, new Input("development-only", "(u+v)*(u-v)+v*v+109"), Profile.UNGATED_CONTINUATIONS);
        assertEquals(hash, bound.contentHash());
        assertThrows(IllegalArgumentException.class, () -> LEARNER.apply(bound,
            new Input("overlap", "x*x+23"), Profile.LEARNED_DISPATCH));
    }

    @Test void matchingLimitsAreValidatedWithoutChangingAnyProductionDefaults() {
        assertThrows(IllegalArgumentException.class, () -> new BindingLimits(0, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> new BindingLimits(32, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new BindingLimits(32, 100, 0));
        assertThrows(NullPointerException.class, () -> LEARNER.trainBindingAware(historical.formation(),
            TraceStrategyDispatchExample.selectionInputs(), historical.limits(), null));
    }

    @Test void actualContinuationPreservesScopedSymbolsWithoutDisplayNameRoundTrips() {
        var scope = new de.regelsuche.symbol.SymbolScope(new java.util.UUID(0, 7));
        scope.alias("alias", scope.resolve("y"));
        var input = de.regelsuche.symbol.SymbolicExpression.parse("(x+y)*(x-y)+alias*alias+113", scope);
        String source = de.regelsuche.parse.ExpressionFormatter.format(input.expression());
        var batch = TraceStrategyDispatchLearner.engine(bound, Profile.UNGATED_CONTINUATIONS).transformMeasured(source);
        var step = batch.transformations().getFirst();
        assertEquals(3, step.primitiveStepCount());
        var target = de.regelsuche.symbol.SymbolicExpression.parse("x^2+113", scope).expression();
        assertEquals(target, new de.regelsuche.parse.ExpressionParser().parseTerm(step.transformedExpression()));
        assertEquals(3, new TraceRewriteStrategyLearner().audit(source, List.of(step)));
        assertThrows(IllegalArgumentException.class, () -> LEARNER.trainBindingAware(historical.formation(),
            TraceStrategyDispatchExample.selectionInputs(), historical.limits(), new BindingLimits(32, 1, 20_000)));
    }
}
