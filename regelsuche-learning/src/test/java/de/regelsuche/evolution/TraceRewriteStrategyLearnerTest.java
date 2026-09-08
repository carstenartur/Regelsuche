package de.regelsuche.evolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.evolution.TraceRewriteStrategyLearner.Input;
import de.regelsuche.evolution.TraceRewriteStrategyLearner.Profile;
import de.regelsuche.math.algorithms.equivalence.ExactPolynomialAnalysis;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Budget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class TraceRewriteStrategyLearnerTest {
    private static final TraceRewriteStrategyLearner LEARNER = new TraceRewriteStrategyLearner();
    private static TraceRewriteStrategyLearner.FrozenStrategy strategy;

    @BeforeAll
    static void train() {
        strategy = LEARNER.learn(TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits());
    }

    @Test
    void learnsRealBranchingSequencesAndRetainsTheirPrimitiveReplay() {
        var program = strategy.plan().orElseThrow().toReadableProgram();
        assertTrue(program.contains("choice"));
        assertTrue(program.contains("sequence"));
        assertNotEquals(strategy.plan(), strategy.shuffledPlan());
        assertTrue(strategy.toCanonicalJson().contains("\"orderAblationChanged\":true"));
        assertEquals(4, strategy.observations().size());
        assertEquals(3, strategy.observations().stream().filter(o -> !o.geneSequence().isEmpty()).count());
        for (var observation : strategy.observations()) {
            if (!observation.geneSequence().isEmpty()) {
                assertTrue(observation.geneSequence().size() >= 2);
                assertEquals(observation.search().bestState().primitiveDepth(), observation.geneSequence().size());
                var proof = observation.minimality().orElseThrow();
                assertTrue(proof.minimumProved());
                assertEquals(observation.geneSequence().size(), proof.minimumPrimitiveSteps());
                assertEquals(strategy.inventory().contentHash(), proof.inventoryHash());
            }
            assertEquals(observation.search().bestState().primitiveDepth(), observation.exactAuditCalls());
            assertTrue(observation.replayWorkUnits() > 0);
            assertFalse(observation.search().reached());
            assertEquals("", observation.search().configuration().targetExpression());
            assertFalse(program.contains(observation.input().expression()));
            for (var state : observation.search().exploredStates()) {
                assertTrue(strategy.excludedIdentities().contains(new ExactPolynomialAnalysis().alphaIdentity(state.expression())));
            }
        }
        assertTrue(strategy.trainingSearchWorkUnits() > strategy.trainingReplayWorkUnits());
        assertTrue(strategy.trainingMinimalityWorkUnits() > 0);
    }

    @Test
    void inconclusiveMinimalityCannotProduceALearnedProgram() {
        var original = strategy.limits();
        var limited = new TraceRewriteStrategyLearner.Limits(original.trainingBudget(), original.maximumInputs(),
            original.maximumTraceSteps(), original.maximumProgramNodes(), new PrimitiveTraceMinimalityVerifier.Limits(1, 65_536, 512, 1024, 500_000));
        var rejected = LEARNER.learn(strategy.inventory(), TraceStrategyTransferExample.trainingInputs(), limited);
        assertTrue(rejected.plan().isEmpty());
        assertTrue(rejected.shuffledPlan().isEmpty());
        assertTrue(rejected.observations().stream().allMatch(o -> o.geneSequence().isEmpty()));
        assertTrue(rejected.observations().stream().flatMap(o -> o.minimality().stream()).allMatch(proof ->
            proof.status() == PrimitiveTraceMinimalityVerifier.Status.INCONCLUSIVE));
        assertTrue(rejected.trainingMinimalityWorkUnits() > 0, "rejected checks remain charged");
    }

    @Test
    void formationUsesTheShortestTraceEvenWhenOrdinarySearchExcludesTheShortcut() {
        var original = strategy.inventory();
        var shortcut = new EvolutionGenome.RewriteGene("shortcut", "(?A^2-?B^2)+?B*?B", "?A^2",
            de.regelsuche.transform.RewriteKind.EXPAND, false, -2, 8, 32, List.of(), original.rewrites().getFirst().evidenceObligations());
        var genes = new ArrayList<>(original.rewrites());
        genes.add(shortcut);
        var inventory = original.withRewrites(genes);
        var originalLimits = strategy.limits();
        var limits = new TraceRewriteStrategyLearner.Limits(Budget.primitive(6, 80, 32, 0, 30_000),
            originalLimits.maximumInputs(), originalLimits.maximumTraceSteps(), originalLimits.maximumProgramNodes());
        var inputs = TraceStrategyTransferExample.trainingInputs().subList(0, 2);
        var shortest = LEARNER.learn(inventory, inputs, limits);
        for (var observation : shortest.observations()) {
            assertEquals(3, observation.search().bestState().primitiveDepth());
            assertEquals(PrimitiveTraceMinimalityVerifier.Status.SHORTER_PATH_FOUND, observation.minimality().orElseThrow().status());
            assertEquals(List.of("difference-product", "shortcut"), observation.geneSequence());
        }
        genes.set(genes.size() - 1, shortcut.withPatterns("(?A+?B)*(?A-?B)+?B*?B", "?A^2"));
        var redundant = LEARNER.learn(original.withRewrites(genes), inputs, limits);
        assertTrue(redundant.plan().isEmpty(), "an existing one-step connection cannot become a new macro");
        assertTrue(redundant.observations().stream().allMatch(o -> o.minimality().orElseThrow().minimumPrimitiveSteps() == 1));
    }

    @Test
    void trainingOrderIsIrrelevantButDifferentTrainingChangesTheProgram() {
        var reversed = new ArrayList<>(TraceStrategyTransferExample.trainingInputs());
        Collections.reverse(reversed);
        var reordered = LEARNER.learn(strategy.inventory(), reversed, strategy.limits());
        assertEquals(strategy.toCanonicalJson(), reordered.toCanonicalJson());
        var onlyCancellation = LEARNER.learn(strategy.inventory(),
            TraceStrategyTransferExample.trainingInputs().subList(0, 2), strategy.limits());
        var onlyFactoring = LEARNER.learn(strategy.inventory(),
            TraceStrategyTransferExample.trainingInputs().subList(2, 4), strategy.limits());
        assertNotEquals(onlyCancellation.plan(), onlyFactoring.plan());
        assertFalse(onlyCancellation.plan().orElseThrow().toReadableProgram().contains("factor-left"));
        assertFalse(onlyFactoring.plan().orElseThrow().toReadableProgram().contains("difference-product"));
        assertThrows(UnsupportedOperationException.class, () -> strategy.observations().clear());
    }

    @Test
    void appliesToNewCompositionsWithoutChangingTheFrozenTrainingModel() {
        String frozen = strategy.toCanonicalJson();
        String source = "((j+k)*(j-k)+k*k)*(j+2)";
        var application = LEARNER.apply(strategy, source, strategy.limits().trainingBudget(), Profile.LEARNED_PROGRAM);
        assertTrue(application.search().bestState().programUsed());
        assertEquals(3, application.search().bestState().primitiveDepth());
        assertEquals(3, application.exactAuditCalls());
        assertEquals("j ^ 2 * (j + 2)", application.search().bestState().expression());
        new ExactPolynomialAnalysis().requireEquivalent(source, application.search().bestState().expression());
        assertEquals(frozen, strategy.toCanonicalJson());
        for (String leaked : List.of("z*z+3", "3+z^2", "(z+7)*(z-7)+52", "5*z^2", "(s+t)*s")) {
            for (var profile : Profile.values()) {
                assertTrue(assertThrows(IllegalArgumentException.class, () -> LEARNER.apply(strategy,
                    leaked, strategy.limits().trainingBudget(), profile)).getMessage().contains("TRAIN polynomial"));
            }
        }
    }

    @Test
    void programsCannotHideTheirPrimitiveDepthBehindASingleEdge() {
        var budget = Budget.primitive(1, 80, 32, 1, 30_000);
        var application = LEARNER.apply(strategy, "((j+k)*(j-k)+k*k)*(j+2)", budget, Profile.LEARNED_PROGRAM);
        assertTrue(application.search().metrics().primitiveBudgetPrunes() > 0);
        assertTrue(application.search().bestState().primitiveDepth() <= 1);
        assertNotEquals("j ^ 2 * (j + 2)", application.search().bestState().expression());
        assertTrue(application.search().metrics().chargedSearchWorkUnits() <= budget.maxWorkUnits());
    }

    @Test
    void noImprovingMultistepTraceMeansNoInventedStrategy() {
        var empty = LEARNER.learn(strategy.inventory(), List.of(new Input("first", "x+11"),
            new Input("second", "y+13")), strategy.limits());
        assertTrue(empty.plan().isEmpty());
        assertTrue(empty.shuffledPlan().isEmpty());
        assertEquals(2, empty.observations().size());
        assertTrue(empty.observations().stream().allMatch(o -> o.geneSequence().isEmpty()));
        var flat = LEARNER.apply(empty, "z+17", strategy.limits().trainingBudget(), Profile.FLAT_RULES);
        var learned = LEARNER.apply(empty, "z+17", strategy.limits().trainingBudget(), Profile.LEARNED_PROGRAM);
        assertEquals(flat.search(), learned.search());
    }

    @Test
    void rejectsFalseRulesUnsupportedInventoriesAndDuplicateTrainingBeforeLearning() {
        var rule = strategy.inventory().rewrites().getFirst();
        for (var invalid : List.of(rule.withPatterns("?A+0", "?A+1"),
                rule.withPatterns("sin(?A)", "sin(?A)"), rule.withPatterns("?A+x", "x+?A"))) {
            assertThrows(IllegalArgumentException.class, () -> LEARNER.learn(
                strategy.inventory().withRewrites(List.of(invalid)), TraceStrategyTransferExample.trainingInputs(), strategy.limits()));
        }
        assertThrows(IllegalArgumentException.class, () -> LEARNER.learn(strategy.inventory(),
            List.of(new Input("first", "x*(x+1)"), new Input("second", "y^2+y")), strategy.limits()));
        for (String unsupported : List.of("sin(x)+1", "x/(y+1)", "a+b+c+d+e", "(x^32)^32")) {
            assertThrows(IllegalArgumentException.class, () -> LEARNER.apply(strategy,
                unsupported, strategy.limits().trainingBudget(), Profile.LEARNED_PROGRAM));
        }
    }
}
