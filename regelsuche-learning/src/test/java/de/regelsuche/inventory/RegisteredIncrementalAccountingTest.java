package de.regelsuche.inventory;

import static de.regelsuche.search.moves.IncrementalProviderContract.*;
import static de.regelsuche.inventory.WorkReplacementManifest.*;
import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

import de.regelsuche.search.moves.*;
import de.regelsuche.transform.Transformation;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RegisteredIncrementalAccountingTest {
    @Test void sourceOnlyQualityAndFixedBudgetCannotClaimAnIncompleteSubtotalFits() {
        for (var mode : QualityMode.values()) {
            var query = query("first");
            var search = new TypedSourceOnlySearch();
            var result = mode == QualityMode.SUFFICIENT_QUALITY_MIN_WORK
                ? search.searchUntil(query.problem(), query.objective(), 1, SearchContinuationContract.PATH_SENSITIVE)
                : search.search(query.problem(), query.objective());
            assertEquals(1, result.outputScore());
            assertTrue(result.totalWork() < result.workBudget());
            assertFalse(result.withinBudget(), "unknown delegated cleanup work cannot be treated as zero");
            assertTrue(result.replayWork() > 0);
            assertTrue(result.witness().getFirst().verification().accepted());
        }
    }

    @Test void lifecycleAdapterKeepsValidProofAndPaidWorkButMarksTheJournalIncomplete() {
        for (var mode : QualityMode.values()) {
            var query = query("first");
            var journal = new WorkReplacementExperiment.Journal();
            var evaluation = execution(query).execute(query, 100000, quality(mode), journal, mode.name());
            assertFalse(journal.complete());
            assertFalse(evaluation.qualityReached());
            assertTrue(evaluation.validProof());
            assertEquals(1, evaluation.outputScore());
            assertTrue(journal.account().work(QUERY) > 0);
            assertTrue(journal.account().work(FINAL_CHECK) > 0);
            assertTrue(journal.account().work(OUTPUT) > 0);
        }
    }

    @Test void incompleteQueryRetainsAnExplicitRowAndStopsTheRemainingStream() {
        var queries = List.of(query("first"), query("second"));
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 1000000, queries.stream()
            .map(query -> new Partition(query.id(), query.sourceIdentity(), "zero", Split.FINAL_TEST, List.of())).toList());
        var plans = new EnumMap<Arm, WorkReplacementExperiment.Plan>(Arm.class);
        for (var arm : Arm.values()) plans.put(arm, new WorkReplacementExperiment.Plan(manifest.binding(arm),
            journal -> {}, LifecycleWorkAccount.empty(), (journal, prefix) -> execution(queries.getFirst())));
        var report = new WorkReplacementExperiment().run(manifest, queries, plans);
        var result = report.arms().get(Arm.B1);
        assertEquals("ACCOUNTING_INCOMPLETE", result.rows().getFirst().status().name());
        assertTrue(result.rows().getFirst().evaluation().validProof());
        assertEquals(WorkReplacementExperiment.Status.NOT_RUN, result.rows().getLast().status());
        assertEquals(0, result.rows().getLast().allocatedWork());
        assertFalse(result.withinBudget(1000000));
        assertEquals(0, report.successes(Arm.B1));
    }

    private static WorkReplacementTypedExecution execution(WorkReplacementExperiment.Query query) {
        return new WorkReplacementTypedExecution(new TypedSourcePolicySelection.Profile("registered", query.problem().providers(),
            MovePriorityPolicy.INVENTORY_ORDER), false, List.of());
    }
    private static Quality quality(QualityMode mode) {
        return new Quality("node-count/v1", mode, 1, SearchContinuationContract.PATH_SENSITIVE);
    }
    private static WorkReplacementExperiment.Query query(String id) {
        var base = WorkReplacementExperimentTest.query(id);
        var p = base.problem();
        var provider = provider();
        var problem = new TypedMoveSearch.Problem(p.source(), p.context(), List.of(provider), p.policy(), p.verifier(),
            p.stateScore(), p.mode(), MoveSearch.Scheduling.STAGED_INCREMENTAL, p.budget());
        return new WorkReplacementExperiment.Query(id, base.sourceIdentity(), problem, base.objective());
    }
    private static RegisteredIncrementalMoveProvider provider() {
        var primitive = WorkReplacementExperimentTest.PROFILE.providers().getFirst();
        var definition = new Definition(IncrementalProviderContract.REVISION, "registered-zero", Kind.REGISTERED_SCHEMA, "fixture/v1", "scalar/v1",
            Transport.TYPED_AST_JSON, Mathematics.PRIMITIVE, null);
        var descriptor = new MoveProvider.Descriptor("registered-zero", "zero", SearchMove.SourceKind.LEARNED,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture/v1");
        return new RegisteredIncrementalMoveProvider(descriptor, definition, new Registry(List.of(new Registration(definition,
            (state, context, meter) -> failingCleanupSource(primitive, state, context, meter)))));
    }
    private static Source failingCleanupSource(MoveProvider primitive, MoveState state, MoveContext context, Meter meter) {
        return new Source() {
            @Override public Optional<Transformation> next(long allowance) {
                var batch = primitive.candidates(state, context);
                meter.charge(Operation.MATCH, batch.work().totalWorkUnits()); meter.charge(batch.work().candidateWork());
                return batch.moves().stream().findFirst().map(SearchMove::transformation);
            }
            @Override public Status status() { return Status.EXHAUSTED; }
            @Override public void close() {
                meter.charge(Operation.CLOSE, 7);
                throw new IllegalStateException("delegated close remainder is unobservable");
            }
        };
    }
}
