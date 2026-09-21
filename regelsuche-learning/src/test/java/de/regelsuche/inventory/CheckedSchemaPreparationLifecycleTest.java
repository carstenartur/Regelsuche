package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;

import de.regelsuche.evolution.*;
import de.regelsuche.parse.ExpressionParser;
import de.regelsuche.search.moves.*;
import de.regelsuche.search.program.CompiledAstReplayCodec;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Diagnostic application/setup contract, not an empirical L1 selection or economic-success claim. */
@Timeout(180)
class CheckedSchemaPreparationLifecycleTest {
    private static final List<String> QUERIES = List.of("(x+y)*(x-y)+y*y", "(u+v)*(u-v)+v*v");
    private static final long BUDGET = 20_000_000;
    private static final long DEADLINE = TimeUnit.SECONDS.toNanos(45);
    private static final CompiledAstReplayCodec CODEC = new CompiledAstReplayCodec();
    private static CheckedLearnedSchemaModel acquired;
    private static LifecycleWorkAccount acquisition;
    private static String suppliedSchema;

    @BeforeAll static void actuallyAcquireAndAccountForTheDiagnosticHint() {
        var partitions = TraceStrategyTransferExample.trainingInputs().stream().map(input ->
            new WorkReplacementManifest.Partition(input.id(), WorkReplacementLearning.identity(input.expression()),
                "public-development", WorkReplacementManifest.Split.TRAIN, List.of())).toList();
        var manifest = WorkReplacementManifestTest.manifest(WorkReplacementManifest.Profile.LOADED_STREAM, BUDGET, partitions);
        var journal = new WorkReplacementExperiment.Journal();
        acquired = WorkReplacementLearning.acquire(manifest, TraceStrategyTransferExample.inventory(),
            TraceStrategyTransferExample.trainingInputs(), TraceStrategyTransferExample.limits(), journal, "acquire").model();
        var hint = acquired.providers().getFirst().candidates(MoveState.root(WorkReplacementLearning.identity(QUERIES.getFirst())),
            MoveContext.frozen("unused"));
        suppliedSchema = hint.moves().stream().filter(move -> WorkReplacementProcessFixture.nodes(
            CODEC.decodeExpression(move.transformation().transformedExpression())) <= 3)
            .findFirst().orElseThrow().transformation().rule();
        WorkReplacementTypedExecution.charge(journal, "hint", SELECTION_TRAINING, hint.work().totalWorkUnitsV2(),
            "diagnostic-schema-hint/v1", suppliedSchema);
        acquisition = journal.account();
    }

    @Test void warmPreparationIsPaidOnceAndDoesNotRecompilePerRequest() {
        var journal = new WorkReplacementExperiment.Journal();
        journal.append(acquisition);
        var restored = WorkReplacementLearning.restore(acquired.toCanonicalJson(), acquired.inventoryHash(), journal, "warm");
        var prepared = WorkReplacementLearning.prepareSchemas(restored, 1, Map.of(), Set.of(suppliedSchema), journal, "warm");
        long setup = journal.account().work(COMPILATION);
        var profile = new TypedSourcePolicySelection.Profile("diagnostic", List.of(prepared.provider()), MovePriorityPolicy.INVENTORY_ORDER);
        var session = new WorkReplacementTypedExecution(profile, false, List.of());
        for (int i = 0; i < QUERIES.size(); i++) {
            long share = (BUDGET - journal.account().totalWork()) / (QUERIES.size() - i);
            var result = session.execute(query(QUERIES.get(i), restored.verifier()), share, quality(), journal, "warm/" + i);
            assertChecked(result);
            assertEquals(setup, journal.account().work(COMPILATION));
        }
        assertEquals(1, preparationCount(journal.account()));
        assertTrue(journal.account().work(FINAL_CHECK) > 0);
        assertTrue(journal.account().work(OUTPUT) > 0);
        assertTrue(journal.complete());
        assertTrue(journal.account().totalWork() <= BUDGET);
        System.out.printf("P03_SETUP warmTotal=%d acquire=%d compile=%d restore=%d query=%d final=%d output=%d%n",
            journal.account().totalWork(), acquisition.totalWork(), journal.account().work(COMPILATION),
            journal.account().work(RESTORE_REPROOF), journal.account().work(QUERY),
            journal.account().work(FINAL_CHECK), journal.account().work(OUTPUT));
    }

    @Test void separateProcessesEachReproveAndPrepareWhileThePrimitiveControlDoesNeither() {
        var journal = new WorkReplacementExperiment.Journal(); journal.append(acquisition);
        long previousPid = -1;
        for (int i = 0; i < QUERIES.size(); i++) {
            long beforeRestore = journal.account().work(RESTORE_REPROOF);
            try (var child = child(true, journal, "cold/" + i)) {
                long pid = child.process().orElseThrow().pid();
                assertNotEquals(previousPid, pid); assertNotEquals(ProcessHandle.current().pid(), pid); previousPid = pid;
                long share = (BUDGET - journal.account().totalWork()) / (QUERIES.size() - i);
                assertChecked(child.execute(query(QUERIES.get(i), acquired.verifier()), share, quality(), journal, "cold/" + i));
            }
            assertTrue(journal.account().work(RESTORE_REPROOF) > beforeRestore);
            assertEquals(i + 1, preparationCount(journal.account()));
        }
        assertTrue(journal.complete());
        assertTrue(journal.account().totalWork() <= BUDGET);
        var baseline = new WorkReplacementExperiment.Journal();
        try (var child = child(false, baseline, "base")) {
            var result = child.execute(query(QUERIES.getFirst(), acquired.verifier()),
                BUDGET - baseline.account().totalWork(), quality(), baseline, "base/0");
            assertTrue(result.validProof());
        }
        assertEquals(0, preparationCount(baseline.account()));
        assertEquals(0, baseline.account().work(RESTORE_REPROOF));
        assertTrue(baseline.complete());
        System.out.printf("P03_SETUP coldTotal=%d preparations=%d restore=%d baselineOneQuery=%d%n",
            journal.account().totalWork(), preparationCount(journal.account()),
            journal.account().work(RESTORE_REPROOF), baseline.account().totalWork());
        // Different stream lengths: deliberately no speedup ratio is computed from these counters.
    }

    private static WorkReplacementLifecycleIntegrationTest.Child child(boolean learned,
            WorkReplacementExperiment.Journal journal, String prefix) {
        return new WorkReplacementLifecycleIntegrationTest.Child(WorkReplacementProcessFixture.class,
            Map.of("learned", learned, "model", learned ? acquired.toCanonicalJson() : "",
                "historical", false, "lazy", true, "oracleSchemaId", learned ? suppliedSchema : ""), DEADLINE, journal, prefix);
    }
    private static WorkReplacementExperiment.Query query(String source, TypedMoveSearch.Verifier verifier) {
        var problem = new TypedMoveSearch.Problem(new ExpressionParser().parseTerm(source),
            TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION), List.of(),
            MovePriorityPolicy.INVENTORY_ORDER, verifier, state -> 0, MoveSearch.Mode.FAST,
            MoveSearch.Scheduling.STAGED_INCREMENTAL, new MoveSearch.Budget(8, 8, 100_000, 64, BUDGET));
        return new WorkReplacementExperiment.Query(source, WorkReplacementLearning.identity(source), problem,
            WorkReplacementProcessFixture::paidScore);
    }
    private static WorkReplacementManifest.Quality quality() {
        return new WorkReplacementManifest.Quality("paid-node-traversal/v1",
            WorkReplacementManifest.QualityMode.SUFFICIENT_QUALITY_MIN_WORK, 3, SearchContinuationContract.PATH_SENSITIVE);
    }
    private static long preparationCount(LifecycleWorkAccount account) {
        return account.receipts().stream().filter(receipt -> receipt.phase() == COMPILATION
            && receipt.rawRevision().equals(CheckedSchemaMatcherPlan.WORK_REVISION)).count();
    }
    private static void assertChecked(WorkReplacementExperiment.Evaluation evaluation) {
        assertTrue(evaluation.validProof()); assertTrue(evaluation.qualityReached());
        assertFalse(evaluation.learnedWitnessIds().isEmpty(), "the restored checked schema must occur in the selected proof");
    }
}
