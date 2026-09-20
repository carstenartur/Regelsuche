package de.regelsuche.inventory;

import static de.regelsuche.inventory.LifecycleWorkAccount.Phase.*;
import static de.regelsuche.inventory.WorkReplacementManifest.*;
import de.regelsuche.evolution.LearnedSchedulingArtifacts;
import de.regelsuche.search.moves.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/** Both B1 and L1 use this adapter over the existing frontier and selected-path checker. */
public final class WorkReplacementTypedExecution implements WorkReplacementExperiment.Session {
    private final TypedSourcePolicySelection.Profile profile;
    private final boolean historical;
    private final List<String> excludedTrainingSources;
    public WorkReplacementTypedExecution(TypedSourcePolicySelection.Profile profile, boolean historical, List<String> excludedTrainingSources) {
        this.profile = Objects.requireNonNull(profile); this.historical = historical;
        this.excludedTrainingSources = List.copyOf(excludedTrainingSources);
    }
    @Override public WorkReplacementExperiment.Evaluation execute(WorkReplacementExperiment.Query query, long allocatedWork,
            Quality quality, WorkReplacementExperiment.Journal journal, String prefix) {
        if (excludedTrainingSources.contains(query.sourceIdentity())) throw new IllegalArgumentException("training source reused for query");
        var p = query.problem(); var b = p.budget();
        var problem = new TypedMoveSearch.Problem(p.source(), p.context(), profile.providers(), profile.policy(), p.verifier(),
            p.stateScore(), p.mode(), p.scheduling(), new MoveSearch.Budget(b.maxPrimitiveSteps(), b.maxSearchDepth(),
                b.maxTheoryWork(), b.maxStates(), allocatedWork, b.maxComplexityDebt()), p.stateValue());
        var search = new TypedSourceOnlySearch();
        TypedSourceOnlySearch.Result result;
        boolean validProof = true;
        String failedCheck = null;
        try {
            result = historical || quality.mode() == QualityMode.BEST_QUALITY_FIXED_BUDGET
                ? search.search(problem, query.objective())
                : search.searchUntil(problem, query.objective(), quality.maximumScore(), quality.continuation());
        } catch (TypedSourceOnlySearch.FinalCheckFailure failure) {
            result = failure.attempted(); validProof = false;
            failedCheck = LearnedSchedulingArtifacts.json(failure.rejected());
        }
        String raw = LearnedSchedulingArtifacts.resultJson(result.search().encodedResult());
        var queryAccount = queryAccount(prefix, result, raw);
        journal.append(queryAccount);
        String finalCheck = failedCheck == null ? result.witness().toString() : failedCheck;
        charge(journal, prefix + "/check", FINAL_CHECK, result.replayWork(), "typed-source-final-replay/v3", finalCheck);
        String output = new de.regelsuche.search.program.CompiledAstReplayCodec().encodeExpression(result.incumbent().expression());
        // Logical output units are UTF-8 bytes materialized, distinct from CPU time and disk IO.
        long queryBytes = queryAccount.receipts().stream().mapToLong(receipt -> receipt.rawReceipt().getBytes(StandardCharsets.UTF_8).length)
            .reduce(0, Math::addExact);
        long outputBytes = Math.addExact(queryBytes, Math.addExact((long) finalCheck.getBytes(StandardCharsets.UTF_8).length,
            output.getBytes(StandardCharsets.UTF_8).length));
        charge(journal, prefix + "/output", OUTPUT, outputBytes, "utf8-materialized-bytes/v1", "");
        return new WorkReplacementExperiment.Evaluation(result.outputScore() <= quality.maximumScore(), validProof,
            result.inputScore(), result.outputScore(), output, raw,
            result.witness().stream().filter(step -> step.move().sourceKind() == SearchMove.SourceKind.LEARNED)
                .map(step -> step.move().ruleId()).distinct().toList());
    }
    private static LifecycleWorkAccount queryAccount(String prefix, TypedSourceOnlySearch.Result result, String raw) {
        var receipts = new java.util.ArrayList<LifecycleWorkAccount.Receipt>();
        var children = new java.util.ArrayList<String>();
        for (var entry : new java.util.TreeMap<>(result.search().metrics().chargedComponents()).entrySet()) {
            String id = prefix + "/query/" + entry.getKey(); children.add(id);
            receipts.add(new LifecycleWorkAccount.Receipt(id, QUERY, entry.getValue(), entry.getValue(), List.of(),
                "move-search-components/v3", entry.getValue().toString(), entry.getValue() == 0 ? "no charged operations" : ""));
        }
        String root = prefix + "/query";
        receipts.add(new LifecycleWorkAccount.Receipt(root, QUERY, result.selectionWork(), result.queryWork(), children,
            "typed-source-query/v3", raw, result.queryWork() == 0 ? "no charged operations" : ""));
        return new LifecycleWorkAccount(receipts, List.of(root));
    }
    public static void charge(WorkReplacementExperiment.Journal journal, String id, LifecycleWorkAccount.Phase phase,
            long work, String revision, String raw) {
        var receipt = new LifecycleWorkAccount.Receipt(id, phase, work, work, List.of(), revision, raw,
            work == 0 ? "no operations reported by this phase" : "");
        journal.append(LifecycleWorkAccount.of(receipt));
    }
}
