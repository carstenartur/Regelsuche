package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Costed late work is synthetic; the rewrites, search, proof replay and selector are real. */
class TypedSourcePolicyQualitySelectionTest {
    private static final PatternExpr A = PatternExpr.var("A");
    private static final AstRewriteTransport ZERO = new AstRewriteTransport(List.of(new PatternRewriteRule("zero",
        PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.num(0)), A)), 16, 32);
    private static final TypedMoveSearch.TypedProvider PROVIDER = TypedMoveSearch.primitiveProvider(
        descriptor("zero", SearchMove.SourceKind.PRIMITIVE), ZERO);
    private static final TypedSourceOnlySearch.Objective OBJECTIVE = state ->
        new TypedSourceOnlySearch.Score(cost(state.expression()), 1);

    @Test void selectionPaysOnlyTheContinuationThatWillActuallyBeExecuted() {
        var lateCalls = new AtomicInteger();
        var profiles = List.of(profile("quick-with-late-work", List.of(PROVIDER, late(lateCalls))),
            profile("slower-immediate-work", List.of(padded(PROVIDER, 30))));
        var legacy = new TypedPolicySelection().trainSourceOnly(tasks(10000), profiles, OBJECTIVE);
        assertEquals("slower-immediate-work", legacy.selected().id());
        assertTrue(lateCalls.get() > 0);
        lateCalls.set(0);
        var online = train(tasks(10000), profiles, 0, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals("quick-with-late-work", online.selected().id());
        assertEquals(0, lateCalls.get(), "training must use the same online stopping as evaluation");
        assertTrue(online.trials().stream().flatMap(t -> t.observations().stream())
            .allMatch(o -> o.outcome() == MoveSearch.Outcome.QUALITY_REACHED && o.withinBudget()));
        assertEquals(online.trials().stream().mapToLong(TypedSourcePolicySelection.Trial::totalWork).sum(), online.trainingWork());
        assertTrue(online.trainingWork() > 0);
        var result = online.evaluate(problem("fresh", 10000, MoveContext.Phase.FROZEN_EVALUATION), OBJECTIVE);
        assertEquals(0, lateCalls.get());
        assertEquals(MoveSearch.Outcome.QUALITY_REACHED, result.search().outcome());
        assertTrue(result.withinBudget());
        assertTrue(result.replayWork() > 0);
        assertEquals(online.trials().getFirst().observations().getFirst().totalWork(), result.totalWork());
        System.out.println("QUALITY_SELECTION legacy=" + legacy.selected().id() + " online=" + online.selected().id()
            + " legacyTraining=" + legacy.trainingWork() + " onlineTraining=" + online.trainingWork()
            + " onlineQuery=" + result.totalWork());
    }

    @Test void sufficientQualityRanksPaidWorkBeforeUnrequestedExtraSimplification() {
        var zero = PatternExpr.op(BinaryOperator.ADD, A, PatternExpr.num(0));
        var start = PatternExpr.op(BinaryOperator.MUL, zero, PatternExpr.num(1));
        var coarseRule = new PatternRewriteRule("coarse", start, zero);
        var fullRule = new PatternRewriteRule("full", start, A);
        var all = new AstRewriteTransport(List.of(coarseRule, fullRule), 16, 32);
        var coarse = TypedMoveSearch.primitiveProvider(descriptor("coarse", SearchMove.SourceKind.PRIMITIVE),
            new AstRewriteTransport(List.of(coarseRule), 16, 32));
        var full = TypedMoveSearch.primitiveProvider(descriptor("full", SearchMove.SourceKind.PRIMITIVE),
            new AstRewriteTransport(List.of(fullRule), 16, 32));
        var tasks = List.of("a", "b").stream().map(name -> new TypedPolicySelection.TrainingTask(name,
            new TypedMoveSearch.Problem(new BinaryExpr(input(name), BinaryOperator.MUL, new NumberExpr(1)),
                TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.TRAIN), List.of(),
                MovePriorityPolicy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(all), state -> 0,
                MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(4, 4, 100, 16, 10000)))).toList();
        var selected = train(tasks, List.of(profile("extra", List.of(padded(full, 100))),
            profile("sufficient", List.of(coarse))), 1, SearchContinuationContract.PATH_SENSITIVE);
        assertEquals("sufficient", selected.selected().id());
        assertTrue(selected.trials().getFirst().outputCost() < selected.trials().getLast().outputCost());
        assertTrue(selected.trials().getFirst().totalWork() > selected.trials().getLast().totalWork());
    }

    @Test void replayOverrunIsNotAQualitySuccessForSelection() {
        var p = problem("calibration", 10000, MoveContext.Phase.TRAIN);
        var calibration = new TypedSourceOnlySearch().searchUntil(withProviders(p, List.of(PROVIDER)),
            OBJECTIVE, 0, SearchContinuationContract.PATH_SENSITIVE);
        long budget = calibration.totalWork() - 1;
        var selected = train(tasks(budget), List.of(profile("over-budget", List.of(PROVIDER)),
            profile("within-budget", List.of())), 0, SearchContinuationContract.PATH_SENSITIVE);
        var over = selected.trials().getFirst();
        assertEquals(2, over.violations());
        assertTrue(over.observations().stream().allMatch(o -> o.totalWork() > budget));
        assertEquals("within-budget", selected.selected().id());
        assertTrue(selected.trainingWork() >= over.totalWork());
    }

    @Test void unreachableQualityFallsBackToTheBestAttainableOutput() {
        for (long threshold : List.of(-1L, Long.MIN_VALUE)) {
            var selected = train(tasks(10000), List.of(profile("empty", List.of()), profile("reduce", List.of(PROVIDER))),
                threshold, SearchContinuationContract.PATH_SENSITIVE);
            assertEquals("reduce", selected.selected().id());
            assertTrue(selected.trials().stream().flatMap(t -> t.observations().stream())
                .noneMatch(o -> o.outcome() == MoveSearch.Outcome.QUALITY_REACHED));
        }
    }

    @Test void oneSuccessfulTaskCannotCompensateForAnotherTasksBudgetViolation() {
        var calls = new AtomicInteger();
        var mixed = new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return PROVIDER.descriptor(); }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var batch = PROVIDER.candidates(state, context);
                long extra = calls.getAndIncrement() == 0 ? 100000 : 0;
                return new Batch(batch.moves(), batch.work().plus(TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(extra)),
                    batch.complete());
            }
        };
        var selected = train(tasks(1000), List.of(profile("mixed", List.of(mixed)), profile("valid", List.of())),
            0, SearchContinuationContract.PATH_SENSITIVE);
        var trial = selected.trials().getFirst();
        assertEquals(1, trial.violations());
        assertTrue(trial.observations().getLast().withinBudget());
        assertEquals(0, trial.observations().getLast().outputScore());
        assertEquals("valid", selected.selected().id(), "budget authority remains the primary selection criterion");
    }

    @Test void historicalThreeArgumentFrozenConstructorKeepsTheExactV1Json() {
        var plain = profile("plain", List.of());
        var observation = new TypedSourcePolicySelection.Observation("t", 3, 2, 9, true,
            MoveSearch.Outcome.INCONCLUSIVE, 11, 12, 13);
        var frozen = new TypedSourcePolicySelection.Frozen(plain,
            List.of(new TypedSourcePolicySelection.Trial(plain, List.of(observation))), List.of("source"));
        assertEquals("{\"schema\":\"regelsuche.typed-source-policy-selection/v1\",\"selected\":\"plain\",\"trainingWork\":9,"
            + "\"selection\":\"MIN_BUDGET_VIOLATIONS_THEN_OUTPUT_COST_THEN_FULL_CONTINUATION_WORK\","
            + "\"authority\":\"EMPIRICAL_SCHEDULING_ONLY;NO_MATHEMATICAL_PRUNING\","
            + "\"mode\":\"BUDGETED_HEURISTIC;COMPLETE_REFERENCE_UNCHANGED\","
            + "\"measurement\":\"WALL_AND_PROCESS_CPU;REQUEST_THREAD_ALLOCATIONS;LOGICAL_WORK_IS_NOT_TIME\","
            + "\"trainingSources\":[\"source\"],\"trials\":[{\"profile\":\"plain\",\"work\":9,\"outputCost\":2,\"budgetViolations\":0,"
            + "\"observations\":[{\"task\":\"t\",\"inputScore\":3,\"outputScore\":2,\"totalWork\":9,\"withinBudget\":true,"
            + "\"outcome\":\"INCONCLUSIVE\",\"wallNanos\":11,\"cpuNanos\":12,\"allocatedBytes\":13}]}]}", frozen.toCanonicalJson());
    }

    @Test void qualityControlIsRetainedButDoesNotRewriteTheLegacyArtifact() throws Exception {
        var mapper = new ObjectMapper();
        var profiles = List.of(profile("reduce", List.of(PROVIDER)));
        var legacy = new TypedPolicySelection().trainSourceOnly(tasks(10000), profiles, OBJECTIVE);
        var old = mapper.readTree(legacy.toCanonicalJson());
        assertEquals(TypedSourcePolicySelection.REVISION, old.path("schema").asText());
        assertFalse(old.has("qualityGoal"));
        for (var contract : SearchContinuationContract.values()) {
            var model = train(tasks(10000), profiles, 0, contract);
            var json = mapper.readTree(model.toCanonicalJson());
            assertNotEquals(old.path("schema"), json.path("schema"));
            assertEquals(0, json.path("qualityGoal").path("maximumOutputScore").asLong(-1));
            assertEquals(contract.name(), json.path("qualityGoal").path("continuationContract").asText());
            assertEquals(model.trainingWork(), json.path("trainingWork").asLong());
            var result = model.evaluate(problem("unseen", 10000, MoveContext.Phase.FROZEN_EVALUATION), OBJECTIVE);
            assertEquals(MoveSearch.Outcome.QUALITY_REACHED, result.search().outcome());
            assertTrue(result.withinBudget());
        }
    }

    @Test void frozenEvaluationStillRejectsTrainLeakageAndCompleteReferenceMode() {
        var profiles = List.of(profile("reduce", List.of(PROVIDER)));
        var model = train(tasks(10000), profiles, 0, SearchContinuationContract.DECLARED_STATE_LOCAL);
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(
            problem("a", 10000, MoveContext.Phase.FROZEN_EVALUATION), OBJECTIVE));
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(problem("new", 10000, MoveContext.Phase.TRAIN), OBJECTIVE));
        var p = problem("new", 10000, MoveContext.Phase.FROZEN_EVALUATION);
        var complete = new TypedMoveSearch.Problem(p.source(), p.context(), p.providers(), p.policy(), p.verifier(),
            p.stateScore(), MoveSearch.Mode.COMPLETE_BOUNDED_REFERENCE, p.scheduling(), p.budget());
        assertThrows(IllegalArgumentException.class, () -> model.evaluate(complete, OBJECTIVE));
        assertThrows(NullPointerException.class, () -> train(tasks(10000), profiles, 0, null));
    }

    private static TypedSourcePolicySelection.Frozen train(List<TypedPolicySelection.TrainingTask> tasks,
            List<TypedSourcePolicySelection.Profile> profiles, long maximumScore, SearchContinuationContract contract) {
        return TypedSourcePolicySelection.trainUntil(tasks, profiles, OBJECTIVE, maximumScore, contract);
    }
    private static int cost(Expr expression) {
        return expression instanceof BinaryExpr b ? 1 + cost(b.left()) + cost(b.right()) : 0;
    }
    private static Expr input(String name) {
        return new BinaryExpr(new VariableExpr(name), BinaryOperator.ADD, new NumberExpr(0));
    }
    private static MoveProvider.Descriptor descriptor(String id, SearchMove.SourceKind kind) {
        return new MoveProvider.Descriptor(id, id, kind, SearchMove.ProofStrength.REPLAYABLE,
            List.of(), SearchMove.ValueEvidence.UNKNOWN, "quality-selection-fixture");
    }
    private static TypedSourcePolicySelection.Profile profile(String id, List<MoveProvider> providers) {
        return new TypedSourcePolicySelection.Profile(id, providers, MovePriorityPolicy.INVENTORY_ORDER);
    }
    private static List<TypedPolicySelection.TrainingTask> tasks(long budget) {
        return List.of("a", "b").stream().map(id -> new TypedPolicySelection.TrainingTask(id,
            problem(id, budget, MoveContext.Phase.TRAIN))).toList();
    }
    private static TypedMoveSearch.Problem problem(String name, long budget, MoveContext.Phase phase) {
        return new TypedMoveSearch.Problem(input(name), TypedMoveSearch.Context.sourceOnly(List.of(), phase), List.of(),
            MovePriorityPolicy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(ZERO), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(4, 4, 100, 16, budget));
    }
    private static TypedMoveSearch.Problem withProviders(TypedMoveSearch.Problem p, List<MoveProvider> providers) {
        return new TypedMoveSearch.Problem(p.source(), p.context(), providers, p.policy(), p.verifier(), p.stateScore(),
            p.mode(), p.scheduling(), p.budget(), p.stateValue());
    }
    private static TypedMoveSearch.TypedProvider padded(TypedMoveSearch.TypedProvider delegate, long extra) {
        return new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() { return delegate.descriptor(); }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                var batch = delegate.candidates(state, context);
                return new Batch(batch.moves(), batch.work().plus(TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(extra)),
                    batch.complete());
            }
        };
    }
    private static TypedMoveSearch.TypedProvider late(AtomicInteger calls) {
        return new TypedMoveSearch.TypedProvider() {
            @Override public Descriptor descriptor() {
                return TypedSourcePolicyQualitySelectionTest.descriptor("late", SearchMove.SourceKind.SOLVER);
            }
            @Override public Batch candidates(MoveState state, MoveContext context) {
                calls.incrementAndGet();
                return new Batch(List.of(), TransformationWorkMetrics.ZERO.withDelegatedMechanicalWork(1000), false);
            }
        };
    }
}
