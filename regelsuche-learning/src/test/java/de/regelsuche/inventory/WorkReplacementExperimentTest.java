package de.regelsuche.inventory;

import static org.junit.jupiter.api.Assertions.*;
import static de.regelsuche.inventory.WorkReplacementManifest.*;
import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;
import de.regelsuche.ast.*;
import de.regelsuche.search.moves.*;
import de.regelsuche.transform.*;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkReplacementExperimentTest {
    static final AstRewriteTransport TRANSPORT = new AstRewriteTransport(List.of(new PatternRewriteRule("zero",
        PatternExpr.op(BinaryOperator.ADD, PatternExpr.var("A"), PatternExpr.num(0)), PatternExpr.var("A"))), 16, 32);
    static final TypedSourcePolicySelection.Profile PROFILE = new TypedSourcePolicySelection.Profile("zero", List.of(
        TypedMoveSearch.primitiveProvider(new MoveProvider.Descriptor("zero", "zero", SearchMove.SourceKind.PRIMITIVE,
            SearchMove.ProofStrength.REPLAYABLE, List.of(), SearchMove.ValueEvidence.UNKNOWN, "fixture"), TRANSPORT)),
        MovePriorityPolicy.INVENTORY_ORDER);
    static final TypedSourceOnlySearch.Objective OBJECTIVE = state -> new TypedSourceOnlySearch.Score(
        state.expression() instanceof BinaryExpr ? 3 : 1, 1);
    @Test void finalTestRejectsTrainingContextBeforeAnyCallback() { rejectsTrainingContext(Split.FINAL_TEST); }
    @Test void validationRejectsTrainingContextBeforeAnyCallback() { rejectsTrainingContext(Split.VALIDATION); }
    private static void rejectsTrainingContext(Split split) {
        var original = query("query"); var p = original.problem();
        var training = new TypedMoveSearch.Problem(p.source(), TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.TRAIN),
            p.providers(), p.policy(), p.verifier(), p.stateScore(), p.mode(), p.scheduling(), p.budget());
        var query = new WorkReplacementExperiment.Query(original.id(), original.sourceIdentity(), training, original.objective());
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000,
            List.of(new Partition("query", query.sourceIdentity(), "query-family", split, List.of())));
        var acquisitions = new AtomicInteger(); var sessions = new AtomicInteger();
        var plans = new EnumMap<Arm, WorkReplacementExperiment.Plan>(Arm.class);
        for (var arm : Arm.values()) plans.put(arm, new WorkReplacementExperiment.Plan(manifest.binding(arm),
            journal -> acquisitions.incrementAndGet(), LifecycleWorkAccount.empty(), (journal, prefix) -> {
                sessions.incrementAndGet(); return new WorkReplacementTypedExecution(PROFILE, false, List.of());
            }));
        assertAll(() -> assertThrows(IllegalArgumentException.class, () -> new WorkReplacementExperiment().run(manifest, List.of(query), plans)),
            () -> assertEquals(0, acquisitions.get()), () -> assertEquals(0, sessions.get()));
    }
    @Test void successfulOnlineSearchCanFailLifecycleBudgetAfterFinalReplayAndOutput() {
        var query = query("query");
        var problem = query.problem();
        var actual = new TypedMoveSearch.Problem(problem.source(), problem.context(), PROFILE.providers(), PROFILE.policy(),
            problem.verifier(), problem.stateScore(), problem.mode(), problem.scheduling(), problem.budget());
        var calibration = new TypedSourceOnlySearch().searchUntil(actual, OBJECTIVE, 1, SearchContinuationContract.PATH_SENSITIVE);
        long budget = calibration.totalWork() - 1;
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, budget, WorkReplacementManifestTest.partitions());
        var result = new WorkReplacementExperiment().run(manifest, List.of(query), plans(manifest, 0, new AtomicInteger()));
        var improved = result.arms().get(Arm.B1);
        assertEquals(WorkReplacementExperiment.Status.OVER_BUDGET, improved.rows().getFirst().status());
        assertFalse(improved.withinBudget(budget));
        assertEquals(calibration.replayWork(), improved.account().work(FINAL_CHECK));
        assertTrue(improved.account().totalWork() >= calibration.totalWork());
        assertEquals(0, result.successes(Arm.B1));
        assertTrue(improved.rows().getFirst().evaluation().rawReceipt().contains("QUALITY_REACHED"));
    }
    @Test void finalCheckOutputExceedsThePreviouslyReportedSufficientBudget() {
        var generous = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, WorkReplacementManifestTest.partitions());
        var calibration = new WorkReplacementExperiment().run(generous, List.of(query("query")), plans(generous, 0, new AtomicInteger()));
        var arm = calibration.arms().get(Arm.B1);
        var evaluation = arm.rows().getFirst().evaluation();
        long oldBudget = Math.addExact(arm.account().work(QUERY), Math.addExact(arm.account().work(FINAL_CHECK),
            Math.addExact(utf8(evaluation.rawReceipt()), utf8(evaluation.outputIdentity()))));
        var tight = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, oldBudget, WorkReplacementManifestTest.partitions());
        var report = new WorkReplacementExperiment().run(tight, List.of(query("query")), plans(tight, 0, new AtomicInteger()));
        assertEquals(WorkReplacementExperiment.Status.OVER_BUDGET, report.arms().get(Arm.B1).rows().getFirst().status());
        assertFalse(report.arms().get(Arm.B1).withinBudget(oldBudget));
        assertEquals(0, report.successes(Arm.B1));
        assertOutputAccounted(arm.account(), evaluation);
    }
    @Test void primitiveCompilationPaysForItsGenomeArtifact() {
        var journal = new WorkReplacementExperiment.Journal();
        WorkReplacementLearning.primitives(de.regelsuche.evolution.TraceStrategyTransferExample.inventory(), journal, "primitive");
        assertEquals(utf8(journal.account().receipts().getFirst().rawReceipt()), journal.account().work(OUTPUT));
    }

    @Test void failedFinalReplayRetainsQueryAndFailedVerificationWork() {
        var base = query("query"); var p = base.problem(); var calls = new AtomicInteger();
        var verifier = TypedMoveSearch.primitiveReplay(TRANSPORT);
        var invalid = new TypedMoveSearch.Problem(p.source(), p.context(), p.providers(), p.policy(),
            (source, move, context) -> calls.incrementAndGet() == 1 ? verifier.verify(source, move, context)
                : new MoveVerifier.Verification(false, 9, List.of(), "REPLAY_REJECTED"),
            p.stateScore(), p.mode(), p.scheduling(), p.budget());
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, WorkReplacementManifestTest.partitions());
        var journal = new WorkReplacementExperiment.Journal();
        var evaluation = new WorkReplacementTypedExecution(PROFILE, false, List.of()).execute(
            new WorkReplacementExperiment.Query("query", WorkReplacementLearning.identity("x+0"), invalid, OBJECTIVE), 20000, manifest.quality(), journal, "failed");
        assertFalse(evaluation.validProof());
        assertEquals(9, journal.account().work(FINAL_CHECK));
        assertTrue(journal.account().work(QUERY) > 0);
        assertTrue(journal.complete());
        assertOutputAccounted(journal.account(), evaluation);
    }
    private static void assertOutputAccounted(LifecycleWorkAccount account, WorkReplacementExperiment.Evaluation evaluation) {
        long receiptBytes = account.receipts().stream().filter(receipt -> receipt.phase() == QUERY || receipt.phase() == FINAL_CHECK)
            .mapToLong(receipt -> utf8(receipt.rawReceipt())).reduce(0, Math::addExact);
        assertEquals(Math.addExact(receiptBytes, utf8(evaluation.outputIdentity())), account.work(OUTPUT));
    }
    private static long utf8(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }

    @Test void acquisitionIsPaidAndBaselineReceivesTheUnspentLearningBudget() {
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, WorkReplacementManifestTest.partitions());
        var report = new WorkReplacementExperiment().run(manifest, List.of(query("query")), plans(manifest, 200, new AtomicInteger()));
        assertEquals(200, report.arms().get(Arm.B1).rows().getFirst().allocatedWork()
            - report.arms().get(Arm.L1).rows().getFirst().allocatedWork());
        assertEquals(200, report.arms().get(Arm.L1).account().work(TRAINING_SEARCH));
        assertThrows(IllegalArgumentException.class, () -> report.successes(Arm.L_ORACLE));
        assertThrows(IllegalStateException.class, () -> report.runtimeRatio(Arm.B1, Arm.L1));
    }
    @Test void identicalControlsHaveIdenticalMathAndLogicalReceipts() {
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, WorkReplacementManifestTest.partitions());
        var first = new WorkReplacementExperiment().run(manifest, List.of(query("query")), plans(manifest, 0, new AtomicInteger()));
        var second = new WorkReplacementExperiment().run(manifest, List.of(query("query")), plans(manifest, 0, new AtomicInteger()));
        for (var arm : Arm.values()) {
            assertEquals(first.arms().get(arm).account().toCanonicalJson(), second.arms().get(arm).account().toCanonicalJson());
            assertEquals(first.arms().get(arm).rows().getFirst().evaluation(), second.arms().get(arm).rows().getFirst().evaluation());
        }
    }
    @Test void exhaustedAcquisitionPreservesEveryUnrunRowAndWorkAlreadyDone() {
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 100, WorkReplacementManifestTest.partitions());
        var report = new WorkReplacementExperiment().run(manifest, List.of(query("query")), plans(manifest, 200, new AtomicInteger()));
        assertEquals(4, report.arms().size());
        assertEquals(WorkReplacementExperiment.Status.NOT_RUN, report.arms().get(Arm.L1).rows().getFirst().status());
        assertEquals(200, report.arms().get(Arm.L1).account().totalWork());
        var missing = new EnumMap<>(report.arms()); missing.remove(Arm.B0);
        assertThrows(NullPointerException.class, () -> new WorkReplacementExperiment.Report(manifest, report.queries(), missing));
    }
    @Test void provisionedModelRetainsAcquisitionWithoutRetrainingAndFreshProcessCannotBeFaked() {
        for (var profile : List.of(Profile.PROVISIONED_MODEL, Profile.FRESH_PROCESS_PER_QUERY)) {
            var manifest = WorkReplacementManifestTest.manifest(profile, 20000, WorkReplacementManifestTest.partitions());
            var acquisitionCalls = new AtomicInteger();
            var report = new WorkReplacementExperiment().run(manifest, List.of(query("query")), plans(manifest, 200, acquisitionCalls));
            if (profile == Profile.PROVISIONED_MODEL) {
                assertEquals(0, acquisitionCalls.get());
                assertEquals(200, report.arms().get(Arm.L1).account().work(TRAINING_SEARCH));
            } else {
                assertEquals(4, acquisitionCalls.get());
                assertFalse(report.arms().get(Arm.L1).accountingComplete());
                assertEquals(WorkReplacementExperiment.Status.NOT_RUN, report.arms().get(Arm.L1).rows().getFirst().status());
            }
        }
    }
    @Test void exportedRowsRetainExactQualityAndSelectedStructuralOutput() throws Exception {
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, WorkReplacementManifestTest.partitions());
        var report = new WorkReplacementExperiment().run(manifest, List.of(query("query")), plans(manifest, 0, new AtomicInteger()));
        var evaluation = new com.fasterxml.jackson.databind.ObjectMapper().readTree(WorkReplacementArtifacts.json(report))
            .path("arms").get(1).path("rows").get(0).path("evaluation");
        assertEquals(3, evaluation.path("inputScore").asLong(-999));
        assertEquals(1, evaluation.path("outputScore").asLong(-999));
        assertEquals(WorkReplacementLearning.identity("x"), evaluation.path("outputIdentity").asText());
    }

    @Test void outputFailureRetainsAttemptedIoAndInvalidatesTheArmWithoutDroppingRows() {
        var manifest = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, WorkReplacementManifestTest.partitions());
        var plans = plans(manifest, 0, new AtomicInteger());
        plans.put(Arm.L1, new WorkReplacementExperiment.Plan(manifest.binding(Arm.L1), journal -> {}, LifecycleWorkAccount.empty(),
            (journal, prefix) -> (query, budget, quality, running, id) -> {
                WorkReplacementArtifacts.stream("abcdef", new java.io.Writer() {
                    int count;
                    @Override public void write(char[] chars, int offset, int length) throws java.io.IOException {
                        if (++count == 4) throw new java.io.IOException("sink failed");
                    }
                    @Override public void flush() {}
                    @Override public void close() {}
                }, running, id);
                throw new AssertionError("writer must fail");
            }));
        var report = new WorkReplacementExperiment().run(manifest, List.of(query("query")), plans);
        var failed = report.arms().get(Arm.L1);
        assertEquals(4, failed.account().work(OUTPUT));
        assertFalse(failed.accountingComplete());
        assertEquals(WorkReplacementExperiment.Status.ERROR, failed.rows().getFirst().status());
        assertEquals(0, report.successes(Arm.L1));
        assertTrue(WorkReplacementArtifacts.json(report).contains("sink failed"));
    }
    @Test void timeoutAndUnreachableQualityRemainRowsAndNeverBecomeSuccess() {
        var original = WorkReplacementManifestTest.manifest(Profile.LOADED_STREAM, 20000, WorkReplacementManifestTest.partitions());
        var timed = new WorkReplacementManifest(original.baselineCommit(), original.revisions(), original.informationRegime(),
            original.quality(), original.seeds(), new Resources(20000, 1, 64, 8, "REMAINING_EQUAL_SHARE_WITH_CARRY"),
            original.profile(), original.observationMode(), UnsolvedPolicy.PENALIZE_AT_TIMEOUT, original.partitions());
        var report = new WorkReplacementExperiment().run(timed, List.of(query("query")), plans(timed, 0, new AtomicInteger()));
        assertTrue(report.arms().values().stream().allMatch(result -> result.rows().getFirst().status() == WorkReplacementExperiment.Status.TIMEOUT));
        assertEquals(0, report.successes(Arm.L1));
        assertTrue(Double.isFinite(report.runtimeRatio(Arm.B1, Arm.L1)));
        var unreachable = new WorkReplacementManifest(original.baselineCommit(), original.revisions(), original.informationRegime(),
            new Quality("node-count/v1", QualityMode.SUFFICIENT_QUALITY_MIN_WORK, -1, SearchContinuationContract.PATH_SENSITIVE),
            original.seeds(), original.resources(), original.profile(), original.observationMode(), original.unsolvedPolicy(), original.partitions());
        var missed = new WorkReplacementExperiment().run(unreachable, List.of(query("query")), plans(unreachable, 0, new AtomicInteger()));
        assertEquals(WorkReplacementExperiment.Status.QUALITY_UNREACHED, missed.arms().get(Arm.L1).rows().getFirst().status());
    }

    static WorkReplacementExperiment.Query query(String id) {
        var source = new BinaryExpr(new VariableExpr("x"), BinaryOperator.ADD, new NumberExpr(0));
        var problem = new TypedMoveSearch.Problem(source, TypedMoveSearch.Context.sourceOnly(List.of(), MoveContext.Phase.FROZEN_EVALUATION),
            List.of(), MovePriorityPolicy.INVENTORY_ORDER, TypedMoveSearch.primitiveReplay(TRANSPORT), state -> 0,
            MoveSearch.Mode.FAST, MoveSearch.Scheduling.STAGED, new MoveSearch.Budget(4, 4, 100, 16, 20000));
        return new WorkReplacementExperiment.Query(id, WorkReplacementLearning.identity("x+0"), problem, OBJECTIVE);
    }
    static EnumMap<Arm, WorkReplacementExperiment.Plan> plans(WorkReplacementManifest manifest, long trainingWork, AtomicInteger calls) {
        var result = new EnumMap<Arm, WorkReplacementExperiment.Plan>(Arm.class);
        for (var arm : Arm.values()) {
            var account = arm == Arm.L1 || arm == Arm.L_ORACLE ? paidTraining(trainingWork) : LifecycleWorkAccount.empty();
            result.put(arm, new WorkReplacementExperiment.Plan(manifest.binding(arm), journal -> { calls.incrementAndGet(); journal.append(account); },
                account, (journal, prefix) -> new WorkReplacementTypedExecution(PROFILE, arm == Arm.B0, List.of())));
        }
        return result;
    }
    static LifecycleWorkAccount paidTraining(long work) {
        return work == 0 ? LifecycleWorkAccount.empty() : LifecycleWorkAccount.of(
            LifecycleWorkAccount.Receipt.measured("train", TRAINING_SEARCH, work, "fixture/v1", "training"));
    }
}
