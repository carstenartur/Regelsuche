package de.regelsuche.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.regelsuche.search.moves.*;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Canonical evidence and scientific qualification are a layer above the mathematical search. */
public final class LearnedSchedulingArtifacts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private LearnedSchedulingArtifacts() {}
    public static String modelJson(LearnedSchedulingModel model) {
        return json(Map.of("knowledge", model.knowledge().toCanonicalJson(), "traces", model.traces(), "activity", model.activity(),
            "history", model.history(), "weights", de.regelsuche.inventory.HistoryMovePolicy.Weights.DEFAULT,
            "trainingWork", model.trainingWorkUnits(), "trainingWorkComponents", model.trainingWorkComponents(), "trainingWorkAccountingComplete", true,
            "trainingAccountingScope", "CANONICAL_HOT_PATH_EVENTS_INCLUDING_EXACT_NODES_TERMS_ALPHA_RENAMING_REFERENCE_AND_MEMORY_FEEDBACK_WORK;EXCLUDES_COMPILATION_SERIALIZATION_AND_CPU_BIT_COST"));
    }
    public static String summaryJson(LearnedSchedulingStudy.Report report) {
        var summary = new TreeMap<String, Object>();
        var paired = new TreeMap<String, Map<String, LearnedSchedulingStudy.Row>>();
        for (var row : report.rows()) paired.computeIfAbsent(row.caseId() + ":" + row.budget(), ignored -> new TreeMap<>()).put(row.configuration(), row);
        long regressions = 0, strongRegressions = 0, commonBaseWork = 0, commonRankedWork = 0, commonBaseSearch = 0, commonRankedSearch = 0;
        var gains = new ArrayList<String>(); var strongGains = new ArrayList<String>();
        var frozenIds = report.model().traces().stream().map(LearnedSchedulingModel.Trace::id).collect(java.util.stream.Collectors.toSet());
        for (var pair : paired.values()) {
            var base = pair.get("BASE"); var strong = pair.get("BASE_STAGED"); var ranked = pair.get("LEARNED_RANKED");
            if (base.reached() && !ranked.reached()) regressions++;
            if (strong.reached() && !ranked.reached()) strongRegressions++;
            if (base.reached() && ranked.reached()) {
                commonBaseWork += base.result().metrics().totalWork(); commonRankedWork += ranked.result().metrics().totalWork();
                commonBaseSearch += base.result().metrics().searchWork(); commonRankedSearch += ranked.result().metrics().searchWork();
            }
            if (ranked.reached() && ranked.result().witness().stream().anyMatch(step -> frozenIds.contains(step.move().ruleId()))) {
                if (!base.reached()) gains.add(ranked.id());
                if (!strong.reached()) strongGains.add(ranked.id());
            }
        }
        long assumptions = report.rows().stream().filter(LearnedSchedulingStudy.Row::reached).flatMap(row -> row.result().witness().stream())
            .filter(step -> !step.move().assumptions().isEmpty()).count();
        long invalid = report.rows().stream().filter(LearnedSchedulingStudy.Row::reached).flatMap(row -> row.result().witness().stream())
            .filter(step -> !step.verification().accepted() || step.verification().receipts().size() != step.move().primitiveExpansion().size()).count();
        var byBudget = budgetSummary(report);
        boolean noninferior = byBudget.stream().allMatch(row -> (long) row.get("rankedSolved") >= (long) row.get("baseSolved"));
        boolean reference = report.references().stream().allMatch(LearnedSchedulingStudy.Reference::inclusion);
        boolean gain = !gains.isEmpty() || (commonBaseSearch > 0 && commonRankedSearch <= 0.9 * commonBaseSearch);
        summary.put("schema", LearnedSchedulingProtocol.REVISION); summary.put("modelHash", SchematicProofPlan.hash(modelJson(report.model())));
        summary.put("rows", report.rows().size()); summary.put("byBudget", byBudget); summary.put("regressions", regressions);
        summary.put("regressionsAgainstStagedPrimitives", strongRegressions); summary.put("newlyReachableWitnesses", gains);
        summary.put("newlyReachableAgainstStagedPrimitives", strongGains); summary.put("commonSolvedBaseWork", commonBaseWork);
        summary.put("commonSolvedRankedWork", commonRankedWork); summary.put("commonSolvedBaseSearchWork", commonBaseSearch);
        summary.put("commonSolvedRankedSearchWork", commonRankedSearch); summary.put("correctnessRegressions", invalid);
        summary.put("assumptionRegressions", assumptions); summary.put("referenceInclusion", reference); summary.put("fastNoninferiorityAtEveryBudget", noninferior);
        summary.put("coreLearnedWitnessDemonstrated", !gains.isEmpty()); summary.put("scientificCandidateGate", invalid == 0 && assumptions == 0 && reference && noninferior && gain && !gains.isEmpty());
        summary.put("productionQualified", false); summary.put("productionBoundary", "SEPARATE_AUTHORIZATION_REPLAY_AND_PRODUCTION_QUALIFICATION_REQUIRED_BY_745");
        summary.put("trainingWork", report.model().trainingWorkUnits());
        summary.putAll(amortization(report));
        return json(summary);
    }
    private static Map<String, Object> amortization(LearnedSchedulingStudy.Report report) {
        long budget = LearnedSchedulingProtocol.WORK_BUDGETS.getLast();
        var ranked = new TreeMap<String, LearnedSchedulingStudy.Row>();
        report.rows().stream().filter(row -> row.budget() == budget && row.configuration().equals("LEARNED_RANKED"))
            .forEach(row -> ranked.put(row.caseId(), row));
        long common = 0, saved = 0;
        for (var row : report.rows()) {
            if (row.budget() != budget || !row.configuration().equals("BASE") || !row.reached()) continue;
            var other = ranked.get(row.caseId());
            if (other.reached()) { common++; saved += row.result().metrics().totalWork() - other.result().metrics().totalWork(); }
        }
        var result = new TreeMap<String, Object>();
        result.put("amortizationPoint", saved <= 0 ? null : Math.floorDiv(Math.addExact(Math.multiplyExact(report.model().trainingWorkUnits(), common), saved - 1), saved));
        result.put("amortizationStatus", saved > 0 ? "CANONICAL_WORK_QUERY_PAYBACK" : "NO_POSITIVE_COMMON_SOLVED_WORK_SAVING");
        result.put("amortizationPopulation", "UNIFORM_COMMONLY_SOLVED_HELD_OUT_CASES_AT_FIXED_MAXIMUM_BUDGET;NOT_WALLTIME_OR_CPU_PAYBACK");
        result.put("amortizationCommonCases", common); result.put("amortizationPairedWorkSaved", saved); result.put("amortizationBudget", budget);
        return result;
    }
    private static List<Map<String, Object>> budgetSummary(LearnedSchedulingStudy.Report report) {
        var result = new ArrayList<Map<String, Object>>();
        for (long budget : LearnedSchedulingProtocol.WORK_BUDGETS) {
            var row = new TreeMap<String, Object>(); row.put("budget", budget);
            row.put("baseSolved", solved(report, "BASE", budget)); row.put("rankedSolved", solved(report, "LEARNED_RANKED", budget));
            row.put("naiveSolved", solved(report, "LEARNED_NAIVE", budget)); row.put("expertSolved", solved(report, "EXPERT", budget));
            row.put("stagedPrimitiveSolved", solved(report, "BASE_STAGED", budget)); result.add(row);
        }
        return result;
    }
    private static long solved(LearnedSchedulingStudy.Report report, String configuration, long budget) {
        return report.rows().stream().filter(row -> row.configuration().equals(configuration) && row.budget() == budget && row.reached()).count();
    }
    public static Path write(LearnedSchedulingStudy.Report report, Path output) throws IOException {
        var files = new TreeMap<String, String>(); files.put("protocol.json", report.protocol()); files.put("model.json", modelJson(report.model()));
        files.put("summary.json", summaryJson(report)); files.put("results.csv", csv(report));
        for (var row : report.rows()) files.put("runs/" + row.id() + ".json", rowJson(row));
        for (var reference : report.references()) {
            files.put("reference/" + reference.caseId() + "-BASE.json", resultJson(reference.base()));
            files.put("reference/" + reference.caseId() + "-LEARNED.json", resultJson(reference.learned()));
        }
        for (var training : report.model().training()) files.put("train/" + training.id() + ".json", resultJson(training.result()));
        files.put("README.md", "# Frozen learned-knowledge search\n\nAll task/budget/profile rows and their failures are retained. See protocol.json, model.json, results.csv and summary.json. Each retained witness carries its frozen rule ID, full bound primitive expansion and independent exact replay receipts. Reference files enumerate bounded closures with an impossible sentinel target. An incomplete closure is inconclusive.\n\nThis is a public reproducible held-out corpus, not a sealed final test or production qualification. Primitive-only lazy scheduling and single-feature ablations are included. TRAIN and TEST snapshots cannot mutate each other. Atomic overruns count as failures; walltime is a separate diagnostic. Supplementary legacy instrumentation meters exact node/term, alpha-renaming and bounded-reference work without changing frozen v1/v2 replays. Query amortization uses canonical hot-path work over the fixed maximum-budget common-solved population; it is not walltime or CPU payback. Compilation, serialization and coefficient bit complexity remain outside the declared search-work ledger.\n");
        var manifest = new TreeMap<String, String>();
        for (var file : files.entrySet()) { var path = output.resolve(file.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path, file.getValue()); manifest.put(file.getKey(), SchematicProofPlan.hash(file.getValue())); }
        Files.writeString(output.resolve("manifest.json"), json(Map.of("schema", "regelsuche.learned-scheduling-manifest/v1", "files", manifest)));
        var wall = new StringBuilder("run,wallNanos\nTRAIN,").append(report.trainingWallNanos()).append('\n');
        report.rows().forEach(row -> wall.append(row.id()).append(',').append(row.wallNanos()).append('\n'));
        Files.writeString(output.resolve("walltime-diagnostic.csv"), wall.toString());
        return output.resolve("summary.json");
    }
    private static String rowJson(LearnedSchedulingStudy.Row row) {
        var payload = new TreeMap<String, Object>(); payload.put("id", row.id()); payload.put("caseId", row.caseId());
        payload.put("configuration", row.configuration()); payload.put("budget", row.budget()); payload.put("status", row.status());
        payload.put("search", row.result() == null ? null : resultPayload(row.result())); return json(payload);
    }
    public static String resultJson(MoveSearch.Result result) { return json(resultPayload(result)); }
    private static Map<String, Object> resultPayload(MoveSearch.Result result) {
        var payload = new TreeMap<String, Object>(); payload.put("outcome", result.outcome()); payload.put("reached", result.reached());
        if (result.incrementalExecution() != null) payload.put("incrementalExecution", result.incrementalExecution());
        payload.put("completeBoundedRelation", result.completeBoundedRelation()); payload.put("witness", result.witness()); payload.put("events", result.events());
        payload.put("reachedStates", result.reachedStates()); payload.put("deadEndStates", result.deadEndStates()); payload.put("metrics", result.metrics());
        payload.put("totalWork", result.metrics().totalWork()); payload.put("effectiveBranchingFactor", result.metrics().effectiveBranchingFactor());
        payload.put("stateAssessments", result.stateAssessments().entrySet().stream()
            .map(entry -> Map.of("state", entry.getKey(), "assessment", entry.getValue())).sorted(java.util.Comparator.comparing(LearnedSchedulingArtifacts::json)).toList());
        return payload;
    }
    private static String csv(LearnedSchedulingStudy.Report report) {
        var csv = new StringBuilder("case,configuration,budget,status,solved,primitiveWork,searchWork,verificationWork,totalWork,exploredStates,expandedStates,generatedSuccessors,consumedSuccessors,discardedSuccessors,unconsumedSuccessors,effectiveBranchingFactor,firstHitDepth,firstHitPrimitiveDepth,duplicateRate,learnedRuleHitRate,capabilityUnlocks\n");
        for (var row : report.rows()) {
            csv.append(row.caseId()).append(',').append(row.configuration()).append(',').append(row.budget()).append(',').append(row.status()).append(',').append(row.reached());
            if (row.result() == null) { csv.append(",,,,,,,,,,,,,,,,\n"); continue; }
            var m = row.result().metrics(); long learned = row.result().witness().stream().filter(step -> step.move().sourceKind() == SearchMove.SourceKind.LEARNED).count();
            long unlocks = row.result().witness().stream().mapToLong(step -> step.move().capabilityDelta().size()).sum();
            for (Object value : List.of(m.primitiveWork(), m.searchWork(), m.verificationWork(), m.totalWork(), m.exploredStates(), m.expandedStates(),
                    m.generatedSuccessors(), m.consumedSuccessors(), m.discardedSuccessors(), m.unconsumedSuccessors(), m.effectiveBranchingFactor(), m.firstHitDepth(),
                    m.firstHitPrimitiveDepth(), m.consumedSuccessors() == 0 ? 0 : (double) m.duplicates() / m.consumedSuccessors(),
                    row.result().witness().isEmpty() ? 0 : (double) learned / row.result().witness().size(), unlocks)) csv.append(',').append(value);
            csv.append('\n');
        }
        return csv.toString();
    }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(normalize(value)); }
        catch (IOException | ReflectiveOperationException exception) { throw new IllegalStateException("cannot write canonical scheduling evidence", exception); }
    }
    private static Object normalize(Object value) throws ReflectiveOperationException, IOException {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) result.put((String) entry.getKey(), normalize(entry.getValue())); return result;
        }
        if (value instanceof Collection<?> collection) {
            var result = new ArrayList<Object>(); for (Object element : collection) result.add(normalize(element));
            if (value instanceof Set<?>) result.sort(java.util.Comparator.comparing(LearnedSchedulingArtifacts::json)); return result;
        }
        if (value.getClass().isRecord()) {
            var result = new TreeMap<String, Object>();
            for (RecordComponent component : value.getClass().getRecordComponents()) result.put(component.getName(), normalize(component.getAccessor().invoke(value)));
            return result;
        }
        throw new IllegalArgumentException("unsupported evidence value: " + value.getClass());
    }
}
