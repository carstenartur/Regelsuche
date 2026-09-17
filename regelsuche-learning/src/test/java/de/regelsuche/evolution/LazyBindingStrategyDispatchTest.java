package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.canonical.ExpressionCanonicalizer;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.BindingLimits;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.FrozenPolicy;
import de.regelsuche.evolution.TraceStrategyDispatchLearner.Profile;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.scoring.ExpressionScorer;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.strategy.SearchExpansionSource;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkSearchReplay;
import de.regelsuche.symbol.SymbolScope;
import de.regelsuche.symbol.SymbolicExpression;
import de.regelsuche.parse.ExpressionFormatter;
import de.regelsuche.parse.ExpressionParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class LazyBindingStrategyDispatchTest {
    private static final TraceStrategyDispatchLearner LEARNER = new TraceStrategyDispatchLearner();
    private static final BindingLimits LIMITS = new BindingLimits(32, 100_000, 20_000);
    private static FrozenPolicy historical;
    private static FrozenPolicy eager;
    private static FrozenPolicy lazy;

    @BeforeAll static void train() {
        historical = TraceStrategyDispatchExample.train();
        eager = LEARNER.trainBindingAware(historical.formation(), TraceStrategyDispatchExample.selectionInputs(), historical.limits(), LIMITS);
        lazy = trainLazy(LIMITS);
    }

    private static FrozenPolicy trainLazy(BindingLimits limits) {
        return LEARNER.trainBindingAwareLazy(historical.formation(), TraceStrategyDispatchExample.selectionInputs(), historical.limits(), limits);
    }

    @Test void bothHistoricalControlsStayByteIdenticalAndOnlyNewExecutionGetsANewIdentity() {
        assertEquals("sha256:170363d5facca1ff53615fa8197bf70df268a9d872e0915eb5f228c42cddaf43", historical.contentHash());
        assertEquals("sha256:1cec3d6f2554c077e5595bbdf9b3898df922f338f105dbca5c82f64c690a3c46", eager.contentHash());
        assertNotEquals(eager.contentHash(), lazy.contentHash());
        assertTrue(lazy.toCanonicalJson().contains("regelsuche.rule-pattern-sequence/lazy-v1"));
        assertTrue(lazy.toCanonicalJson().contains("regelsuche.trace-binding-model/v3"));
        assertEquals(4, Profile.values().length);
    }

    @Test void sameTemplatesAndFormationAreUsedWithStrictlyLessMeasuredMatchingWork() {
        var baseline = historical.trials().getFirst().observations();
        var oldModel = TraceBindingDispatch.learn(historical.formation(), baseline, LIMITS);
        var newModel = TraceBindingDispatch.learn(historical.formation(), baseline, LIMITS, true);
        assertEquals(oldModel.templates(), newModel.templates());
        assertEquals(oldModel.formationWork(), newModel.formationWork());
        String source = "((x+y)*(x-y)+y*y)+101";
        var old = TraceStrategyDispatchLearner.engine(eager, Profile.UNGATED_CONTINUATIONS).transformMeasured(source);
        var result = TraceStrategyDispatchLearner.engine(lazy, Profile.UNGATED_CONTINUATIONS).transformMeasured(source);
        assertEquals(3, result.transformations().getFirst().primitiveStepCount());
        assertEquals(old.transformations().getFirst().transformedExpression(), result.transformations().getFirst().transformedExpression());
        assertEquals(old.transformations().getFirst().primitiveRuleIds(), result.transformations().getFirst().primitiveRuleIds());
        assertTrue(result.workMetrics().delegatedMechanicalWorkUnits() < old.workMetrics().delegatedMechanicalWorkUnits());
        assertTrue(result.workMetrics().totalWorkUnits() < old.workMetrics().totalWorkUnits());
    }

    @Test void actualPrimitivePathRetainsIndependentAuditAndCompleteSearchReplay() {
        String source = "((x+y)*(x-y)+y*y)+101";
        var engine = TraceStrategyDispatchLearner.engine(lazy, Profile.UNGATED_CONTINUATIONS);
        var step = engine.transformMeasured(source).transformations().getFirst();
        assertEquals(3, new TraceRewriteStrategyLearner().audit(source, List.of(step)));
        new ExactPolynomialAnalysis().requireEquivalent(source, step.transformedExpression());
        var problem = Problem.withoutTarget(source, new SearchExpansionSource.Measured(engine),
            new ExpressionScorer(), new ExpressionCanonicalizer(), historical.limits().budget());
        var result = new WorkBudgetBestFirstSearchStrategy().search(problem);
        assertTrue(result.bestState().programUsed());
        assertEquals(result, WorkSearchReplay.verify(result.toCanonicalJson(), problem));
        var scope = new SymbolScope(new UUID(0, 17));
        scope.alias("alias", scope.resolve("y"));
        String scopedSource = ExpressionFormatter.format(SymbolicExpression.parse("(x+y)*(x-y)+alias*alias+113", scope).expression());
        var scoped = engine.transformMeasured(scopedSource).transformations().getFirst();
        assertEquals(3, scoped.primitiveStepCount());
        assertEquals(SymbolicExpression.parse("x^2+113", scope).expression(), new ExpressionParser().parseTerm(scoped.transformedExpression()));
    }

    @Test void trainingChargesAllTrialsAndStillRejectsRoutesThatDoNotBeatFlatWork() {
        assertEquals(eager.bindingFormationWork(), lazy.bindingFormationWork());
        assertEquals(eager.contextCollectionWork(), lazy.contextCollectionWork());
        assertEquals(eager.trials().getFirst(), lazy.trials().getFirst());
        assertEquals(eager.trials().size(), lazy.trials().size());
        assertTrue(lazy.dispatchLearningWork() < eager.dispatchLearningWork());
        assertEquals(lazy.bindingFormationWork() + lazy.contextCollectionWork()
            + lazy.trials().stream().mapToLong(TraceStrategyDispatchLearner.Trial::measuredWork).sum(), lazy.dispatchLearningWork());
        for (int i = 0; i < lazy.trials().size(); i++) {
            var old = eager.trials().get(i);
            var trial = lazy.trials().get(i);
            assertTrue(trial.measuredWork() <= old.measuredWork());
            assertEquals(old.observations().stream().map(o -> o.search().bestState().score()).toList(),
                trial.observations().stream().map(o -> o.search().bestState().score()).toList());
            if (trial.measuredWork() >= lazy.trials().getFirst().measuredWork()) assertFalse(trial.accepted());
        }
    }

    @Test void failedBindingsAndExhaustionStillUsePrimitiveFallback() {
        var engine = TraceStrategyDispatchLearner.engine(lazy, Profile.UNGATED_CONTINUATIONS);
        for (String source : List.of("(x+y)*(x-y)+z*z+103", "((a+(b+c))+y)*((a+(b+c))-y)+y*y+101")) {
            var step = engine.transformMeasured(source).transformations().getFirst();
            assertEquals(1, step.primitiveStepCount());
            new ExactPolynomialAnalysis().requireEquivalent(source, step.transformedExpression());
        }
        var limited = trainLazy(new BindingLimits(32, 100_000, 1));
        var cut = TraceStrategyDispatchLearner.engine(limited, Profile.UNGATED_CONTINUATIONS)
            .transformMeasured("((x+y)*(x-y)+y*y)+107");
        assertEquals(1, cut.transformations().getFirst().primitiveStepCount());
        assertEquals(1, cut.workMetrics().delegatedMechanicalWorkUnits());
    }

    @Test void fullPathStillRejectsRealValidRewritesAtTheWrongOccurrence() {
        var model = TraceBindingDispatch.learn(historical.formation(), historical.trials().getFirst().observations(), LIMITS, true);
        var flat = TraceStrategyDispatchLearner.engine(historical, Profile.FLAT_EXHAUSTIVE);
        String source = "(x+y)*(x-y)+y*y+(w-z^2+z*z)^2";
        var first = flat.transformMeasured(source).transformations().stream()
            .filter(step -> step.rule().endsWith("difference-product")).findFirst().orElseThrow();
        var second = flat.transformMeasured(first.transformedExpression()).transformations().stream()
            .filter(step -> step.rule().endsWith("square-product") && step.transformedExpression().contains("y * y"))
            .findFirst().orElseThrow();
        var third = flat.transformMeasured(second.transformedExpression()).transformations().stream()
            .filter(step -> step.rule().endsWith("cancel-addend")).findFirst().orElseThrow();
        var suffix = new RewriteCandidate("other-occurrence", first.transformedExpression(), third.transformedExpression(),
            List.of(second, third)).toTransformation();
        new ExactPolynomialAnalysis().requireEquivalent(source, third.transformedExpression());
        var bridge = new TraceBindingDispatch(model);
        var attempt = bridge.begin(List.of("difference-product", "square-product", "cancel-addend"), source, first);
        assertTrue(attempt.eligible());
        assertEquals(2, suffix.primitiveStepCount());
        assertFalse(attempt.accepts(suffix));
        assertTrue(bridge.workUnits() > 0);
    }

    @Test void orderingIsDeterministicAndMatchingLimitsRemainPartOfPolicyIdentity() {
        var reversed = new ArrayList<>(TraceStrategyDispatchExample.selectionInputs());
        Collections.reverse(reversed);
        assertEquals(lazy.toCanonicalJson(), LEARNER.trainBindingAwareLazy(historical.formation(), reversed, historical.limits(), LIMITS).toCanonicalJson());
        assertNotEquals(lazy.contentHash(), trainLazy(new BindingLimits(32, 100_000, 20_001)).contentHash());
        assertThrows(NullPointerException.class, () -> trainLazy(null));
        String hash = lazy.contentHash();
        LEARNER.apply(lazy, new TraceRewriteStrategyLearner.Input("development-only", "(u+v)*(u-v)+v*v+109"), Profile.UNGATED_CONTINUATIONS);
        assertEquals(hash, lazy.contentHash());
    }
}
