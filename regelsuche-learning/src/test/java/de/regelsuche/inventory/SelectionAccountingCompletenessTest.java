package de.regelsuche.inventory;

import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;
import static de.regelsuche.inventory.WorkReplacementManifest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.ast.*;
import de.regelsuche.search.moves.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectionAccountingCompletenessTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void failedSelectionCleanupMarksBothObjectiveAcquisitionsIncomplete() throws Exception {
        for (var mode : QualityMode.values()) {
            var tasks = tasks(10000, false);
            var journal = new WorkReplacementExperiment.Journal();
            var selected = select(manifest(mode, tasks, List.of()), tasks, failingProfiles(), journal);
            assertFalse(journal.complete(), "training cleanup cannot silently become free acquisition work");
            assertEquals(selected.trainingWork(), journal.account().work(SELECTION_TRAINING));
            assertTrue(journal.account().work(OUTPUT) > 0);
            var artifact = JSON.readTree(selected.toCanonicalJson());
            assertEquals("regelsuche.typed-source-policy-selection/v3-accounting", artifact.path("schema").asText());
            assertFalse(artifact.path("accountingComplete").asBoolean(true));
            assertFailedObservations(artifact.path("trials").get(0));
            var receipt = journal.account().receipts().getFirst();
            assertEquals(artifact.path("schema").asText(), receipt.rawRevision());
        }
    }

    @Test void rejectedIncompleteProfileStillPoisonsThePaidAcquisition() throws Exception {
        var tasks = tasks(10000, false);
        var profiles = new ArrayList<>(failingProfiles());
        profiles.add(WorkReplacementExperimentTest.PROFILE);
        for (var mode : QualityMode.values()) {
            var journal = new WorkReplacementExperiment.Journal();
            var selected = select(manifest(mode, tasks, List.of()), tasks, profiles, journal);
            assertEquals(WorkReplacementExperimentTest.PROFILE.id(), selected.selected().id());
            assertFalse(journal.complete(), "losing trials are still paid acquisition work");
            assertEquals(selected.trials().stream().mapToLong(TypedSourcePolicySelection.Trial::totalWork).sum(),
                journal.account().work(SELECTION_TRAINING));
            var artifact = JSON.readTree(selected.toCanonicalJson());
            assertFalse(artifact.path("accountingComplete").asBoolean(true));
            assertFalse(artifact.path("trials").get(0).path("accountingComplete").asBoolean(true));
            assertTrue(artifact.path("trials").get(1).path("accountingComplete").asBoolean(false));
        }
    }

    @Test void incompleteSelectionStopsAcquisitionBeforeAnyRestoreOrEvaluation() {
        var tasks = tasks(10000, false);
        var queries = List.of(WorkReplacementExperimentTest.query("first"), WorkReplacementExperimentTest.query("second"));
        for (var mode : QualityMode.values()) {
            var manifest = manifest(mode, tasks, queries);
            var opens = new AtomicInteger();
            var plans = new EnumMap<Arm, WorkReplacementExperiment.Plan>(Arm.class);
            for (var arm : Arm.values()) plans.put(arm, new WorkReplacementExperiment.Plan(manifest.binding(arm),
                journal -> select(manifest, tasks, failingProfiles(), journal), LifecycleWorkAccount.empty(),
                (journal, prefix) -> { opens.incrementAndGet(); return new WorkReplacementTypedExecution(
                    WorkReplacementExperimentTest.PROFILE, false, List.of()); }));
            var report = new WorkReplacementExperiment().run(manifest, queries, plans);
            assertEquals(0, opens.get(), "unknown acquisition remainder must stop before restoring a query session");
            var result = report.arms().get(Arm.B1);
            assertFalse(result.accountingComplete());
            assertTrue(result.account().work(SELECTION_TRAINING) > 0);
            assertTrue(result.account().work(OUTPUT) > 0);
            assertTrue(result.rows().stream().allMatch(row -> row.status() == WorkReplacementExperiment.Status.NOT_RUN
                && row.allocatedWork() == 0));
            assertEquals(0, report.successes(Arm.B1));
        }
    }

    @Test void knownSelectionOvershootKeepsCompleteAccountingDespiteBudgetViolation() throws Exception {
        var tasks = tasks(5, false);
        for (var mode : QualityMode.values()) {
            var journal = new WorkReplacementExperiment.Journal();
            var selected = select(manifest(mode, tasks, List.of()), tasks,
                List.of(WorkReplacementExperimentTest.PROFILE), journal);
            assertTrue(journal.complete());
            var artifact = JSON.readTree(selected.toCanonicalJson());
            assertEquals("regelsuche.typed-source-policy-selection/v3-accounting", artifact.path("schema").asText());
            assertTrue(artifact.path("accountingComplete").asBoolean(false));
            for (var observed : artifact.path("trials").get(0).path("observations")) {
                assertTrue(observed.path("accountingComplete").asBoolean(false));
                assertFalse(observed.path("withinBudget").asBoolean(true));
                assertTrue(observed.path("totalWork").asLong() > 5);
            }
        }
    }

    @Test void targetSpecificSelectionRetainsTheSameIncompleteAccountingBoundary() throws Exception {
        var tasks = tasks(10000, true);
        var trained = new TypedPolicySelection().train(new RuleHistoryMemory().freeze(), tasks,
            List.of(TypedPolicySelection.Profile.inventoryOrder("plain")));
        var artifact = JSON.readTree(trained.toCanonicalJson());
        assertEquals("regelsuche.typed-policy-selection/v3-accounting", artifact.path("schema").asText());
        assertFalse(artifact.path("accountingComplete").asBoolean(true));
        assertFailedObservations(artifact.path("trials").get(0));
        assertTrue(trained.trainingWork() > 0);
    }

    @Test void targetSpecificKnownOvershootIsStillAccountingComplete() throws Exception {
        var tasks = List.of("a", "b").stream().map(name -> {
            var p = task(name, 5, true).problem();
            return new TypedPolicySelection.TrainingTask(name, new TypedMoveSearch.Problem(p.source(), p.context(),
                WorkReplacementExperimentTest.PROFILE.providers(), p.policy(), p.verifier(), p.stateScore(),
                p.mode(), p.scheduling(), p.budget()));
        }).toList();
        var trained = new TypedPolicySelection().train(new RuleHistoryMemory().freeze(), tasks,
            List.of(TypedPolicySelection.Profile.inventoryOrder("plain")));
        assertTrue(trained.accountingComplete());
        var artifact = JSON.readTree(trained.toCanonicalJson());
        assertEquals(TypedPolicySelection.ACCOUNTING_REVISION, artifact.path("schema").asText());
        for (var observation : trained.trials().getFirst().observations()) {
            assertTrue(observation.accountingComplete());
            assertTrue(observation.metrics().totalWork() > 5);
            assertNotEquals(MoveSearch.Outcome.TARGET_REACHED, observation.outcome());
        }
    }

    private static void assertFailedObservations(JsonNode trial) {
        assertFalse(trial.path("accountingComplete").asBoolean(true));
        assertEquals(2, trial.path("observations").size());
        for (var observation : trial.path("observations")) {
            assertFalse(observation.path("accountingComplete").asBoolean(true));
            assertEquals("INCONCLUSIVE", observation.path("outcome").asText());
            assertEquals(StagedIncrementalMoveExecution.WORK_REVISION, observation.path("executionRevision").asText());
            assertTrue(observation.path("totalWork").asLong() > 0);
        }
    }
    private static TypedSourcePolicySelection.Frozen select(WorkReplacementManifest manifest,
            List<TypedPolicySelection.TrainingTask> tasks, List<TypedSourcePolicySelection.Profile> profiles,
            WorkReplacementExperiment.Journal journal) {
        return WorkReplacementLearning.select(manifest, tasks, profiles, WorkReplacementExperimentTest.OBJECTIVE, journal, "selection");
    }
    private static List<TypedSourcePolicySelection.Profile> failingProfiles() {
        return List.of(new TypedSourcePolicySelection.Profile("failing-close",
            List.of(RegisteredIncrementalAccountingTest.provider()), MovePriorityPolicy.INVENTORY_ORDER));
    }
    private static List<TypedPolicySelection.TrainingTask> tasks(long budget, boolean targeted) {
        return List.of("a", "b").stream().map(name -> task(name, budget, targeted)).toList();
    }
    private static TypedPolicySelection.TrainingTask task(String name, long budget, boolean targeted) {
        var target = new VariableExpr(name);
        var context = targeted ? new TypedMoveSearch.Context(target, List.of(), MoveContext.Phase.TRAIN)
            : TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.TRAIN);
        var problem = new TypedMoveSearch.Problem(new BinaryExpr(target, BinaryOperator.ADD, new NumberExpr(0)), context,
            List.of(RegisteredIncrementalAccountingTest.provider()), MovePriorityPolicy.INVENTORY_ORDER,
            TypedMoveSearch.primitiveReplay(WorkReplacementExperimentTest.TRANSPORT), state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED_INCREMENTAL, new MoveSearch.Budget(4, 4, 100, 16, budget));
        return new TypedPolicySelection.TrainingTask(name, problem);
    }
    private static WorkReplacementManifest manifest(QualityMode mode, List<TypedPolicySelection.TrainingTask> tasks,
            List<WorkReplacementExperiment.Query> queries) {
        var partitions = new ArrayList<Partition>();
        var codec = new de.regelsuche.search.program.CompiledAstReplayCodec();
        tasks.forEach(task -> partitions.add(new Partition(task.id(), codec.encodeExpression(task.problem().source()), "train-zero", Split.TRAIN, List.of())));
        queries.forEach(query -> partitions.add(new Partition(query.id(), query.sourceIdentity(), "query-zero", Split.FINAL_TEST, List.of())));
        var base = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 1000000, partitions);
        return new WorkReplacementManifest(base.baselineCommit(), base.revisions(), base.informationRegime(),
            new Quality("node-count/v1", mode, 1, SearchContinuationContract.PATH_SENSITIVE), base.seeds(), base.resources(),
            base.profile(), base.observationMode(), base.unsolvedPolicy(), partitions);
    }
}
