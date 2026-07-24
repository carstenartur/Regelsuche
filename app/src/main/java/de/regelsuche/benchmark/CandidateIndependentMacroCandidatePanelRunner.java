package de.regelsuche.benchmark;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayEvidence;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.BaselineEvaluation;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.EvaluationTask;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationResult;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.FormationStatus;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.MacroCandidate;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.PairedEvaluation;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.SearchRun;
import de.regelsuche.benchmark.CandidateIndependentReusableMacroAdapter.UtilityOutcome;
import de.regelsuche.discovery.TransformationStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates every TRAIN-formed macro independently against the same verified
 * frozen baseline stream without changing the production winner selection.
 */
public final class CandidateIndependentMacroCandidatePanelRunner {
    public static final String SCHEMA =
        "regelsuche.macro-candidate-panel/v1";
    private static final String BENCHMARK =
        "regelsuche-candidate-independent-autonomous-discovery-2026-07/v1";
    private static final String CHALLENGE = "reusable-search-macros";
    private static final String PROFILE_ID = "macro-primitives/v1";
    private static final String PANEL_POLICY =
        "ALL_TRAIN_FORMED_CANDIDATES_BASELINE_PLUS_EXACTLY_ONE";
    private static final String BASELINE_POLICY =
        "REUSE_VERIFIED_PAIRED_UTILITY_BASELINE_PER_TASK";
    private static final String DECISION_POLICY =
        "DESCRIPTIVE_PANEL_DOES_NOT_RESELECT_PRODUCTION_CANDIDATE";
    private static final int CANDIDATE_COUNT = 3;
    private static final int TASK_COUNT = 12;

    private static final ObjectMapper JSON = new ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    private CandidateIndependentMacroCandidatePanelRunner() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        ObjectNode corpus = readObject(arguments.corpus());
        ObjectNode profile = readObject(arguments.profile());
        validateFormationInputs(corpus, profile);

        List<ObjectNode> cases = macroCases(corpus);
        List<ReplayTrace> traces = trainTraces(cases);
        CandidateIndependentReusableMacroAdapter adapter =
            new CandidateIndependentReusableMacroAdapter(
                operationRules(profile));
        FormationResult formation = adapter.form(traces);
        require(formation.status() == FormationStatus.SELECTED,
            "candidate panel requires selected TRAIN macros");
        require(formation.macros().size() == CANDIDATE_COUNT,
            "candidate panel formed-candidate count changed");
        List<MacroCandidate> candidates = formation.macros().stream()
            .sorted(Comparator.comparing(MacroCandidate::macroId))
            .toList();

        ObjectNode stream = readObject(arguments.downstreamStream());
        ObjectNode pairedUtility = readObject(arguments.pairedUtility());
        validateExecutionInputs(corpus, profile, stream, pairedUtility);
        ArrayNode formedCandidates = requireArray(
            pairedUtility, "formedCandidates", "paired utility candidates");
        validateFormationMatchesUtility(
            candidates, formation.replayEvidence(), pairedUtility);

        LinkedHashMap<String, ObjectNode> retainedCandidates =
            retainedCandidates(formedCandidates);
        List<TaskBinding> tasks = streamTasks(stream);
        LinkedHashMap<String, BaselineBinding> baselines = sharedBaselines(
            pairedUtility, tasks);

        ObjectNode selection = requireObjectField(
            pairedUtility, "candidateSelection", "paired utility selection");
        requireContentHash(selection, "paired utility selection");
        String selectedCandidateId = text(
            selection, "selectedCandidateId");
        String selectedCandidateContentHash = text(
            selection, "selectedCandidateContentHash");
        require(retainedCandidates.containsKey(selectedCandidateId),
            "production-selected candidate is absent from the panel");
        require(text(
                retainedCandidates.get(selectedCandidateId), "contentHash")
                .equals(selectedCandidateContentHash),
            "production-selected candidate content binding drift");

        ArrayNode panelCandidates = JSON.createArrayNode();
        LinkedHashMap<String, Integer> aggregateCounts = emptyOutcomeCounts();
        int candidatesWithRegression = 0;
        int exercisedCandidateCount = 0;
        int totalCandidateStates = 0;
        int totalCandidateGenerated = 0;
        int totalCandidatePathSteps = 0;

        for (MacroCandidate candidate : candidates) {
            boolean productionSelected =
                candidate.macroId().equals(selectedCandidateId);
            FormationResult exactOne = new FormationResult(
                FormationStatus.SELECTED,
                List.of(candidate),
                formation.replayEvidence(),
                "canonical candidate-panel evaluation for "
                    + candidate.macroId());
            ArrayNode evaluations = JSON.createArrayNode();
            LinkedHashMap<String, Integer> counts = emptyOutcomeCounts();
            int correctnessRegressions = 0;
            int candidateStates = 0;
            int candidateGenerated = 0;
            int candidatePathSteps = 0;
            int macroUsageCount = 0;

            for (TaskBinding task : tasks) {
                BaselineBinding baseline = baselines.get(task.task().taskId());
                require(baseline != null,
                    "missing verified baseline for " + task.task().taskId());
                PairedEvaluation evaluation = adapter.evaluate(
                    task.task(),
                    exactOne,
                    new BaselineEvaluation(task.task(), baseline.run()));
                require(evaluation.outcome()
                        != UtilityOutcome.CANDIDATE_NOT_FORMED,
                    "formed panel candidate was reported as not formed");
                List<String> learnedRules = evaluation.macroEnabled()
                    .ruleIds().stream()
                    .filter(ruleId -> ruleId.startsWith(
                        "macro_candidate_independent_"))
                    .toList();
                require(learnedRules.stream()
                        .allMatch(candidate.macroId()::equals),
                    "panel task used a different learned candidate");
                macroUsageCount += learnedRules.size();
                if (evaluation.correctnessRegression()) {
                    correctnessRegressions++;
                }
                counts.merge(
                    evaluation.outcome().name(), 1, Integer::sum);
                aggregateCounts.merge(
                    evaluation.outcome().name(), 1, Integer::sum);
                candidateStates += evaluation.macroEnabled().expandedStates();
                candidateGenerated +=
                    evaluation.macroEnabled().generatedCandidates();
                candidatePathSteps +=
                    evaluation.macroEnabled().ruleIds().size();
                evaluations.add(evaluation(
                    task,
                    baseline,
                    evaluation,
                    candidate.macroId(),
                    productionSelected));
            }

            boolean exercised = macroUsageCount > 0;
            if (productionSelected) {
                require(exercised,
                    "production-selected candidate was not exercised");
            }
            if (exercised) {
                exercisedCandidateCount++;
            }
            require(counts.values().stream().mapToInt(Integer::intValue).sum()
                    == TASK_COUNT,
                "candidate outcome accounting lost frozen tasks");
            if (correctnessRegressions > 0) {
                candidatesWithRegression++;
            }
            totalCandidateStates += candidateStates;
            totalCandidateGenerated += candidateGenerated;
            totalCandidatePathSteps += candidatePathSteps;

            ObjectNode candidateResult = JSON.createObjectNode();
            candidateResult.put("candidateId", candidate.macroId());
            candidateResult.put("candidateContentHash", text(
                retainedCandidates.get(candidate.macroId()), "contentHash"));
            candidateResult.put("trainSupport",
                candidate.supportingTraceIds().size());
            candidateResult.put("productionSelected", productionSelected);
            candidateResult.put("exercised", exercised);
            candidateResult.put("macroUsageCount", macroUsageCount);
            candidateResult.set("outcomeCounts", object(counts));
            candidateResult.put(
                "correctnessRegressionCount", correctnessRegressions);
            candidateResult.set("resourceUse", candidateResourceUse(
                baselines,
                candidateStates,
                candidateGenerated,
                candidatePathSteps));
            candidateResult.set("tasks", evaluations);
            addContentHash(candidateResult);
            panelCandidates.add(candidateResult);
        }

        require(panelCandidates.size() == CANDIDATE_COUNT,
            "candidate panel did not retain every formed candidate");
        require(aggregateCounts.values().stream()
                .mapToInt(Integer::intValue).sum()
                == CANDIDATE_COUNT * TASK_COUNT,
            "panel aggregate outcome accounting drift");
        require(exercisedCandidateCount >= 1,
            "candidate panel did not exercise the production candidate");

        ObjectNode run = JSON.createObjectNode();
        run.put("schema", SCHEMA);
        run.put("benchmarkId", BENCHMARK);
        run.put("challengeId", CHALLENGE);
        run.put("repositoryRevision", arguments.repositoryRevision());
        run.put("caseCorpusContentHash", text(corpus, "contentHash"));
        run.put("baselineInventoryProfileId", PROFILE_ID);
        run.put("baselineInventoryContentHash", text(profile, "contentHash"));
        run.put("downstreamTaskStreamContentHash", text(stream, "contentHash"));
        run.put("pairedTaskUtilityContentHash",
            text(pairedUtility, "contentHash"));
        run.put("formationAccessPolicy", "TRAIN_ONLY");
        run.put("candidatePanelPolicy", PANEL_POLICY);
        run.put("sharedBaselinePolicy", BASELINE_POLICY);
        run.put("decisionPolicy", DECISION_POLICY);
        run.put("formedCandidateCount", candidates.size());
        run.put("evaluatedCandidateCount", panelCandidates.size());
        run.put("tasksPerCandidate", TASK_COUNT);
        run.put("totalCandidateTaskEvaluations",
            CANDIDATE_COUNT * TASK_COUNT);
        run.put("selectedCandidateId", selectedCandidateId);
        run.put("selectedCandidateContentHash",
            selectedCandidateContentHash);
        run.put("productionSelectionPreserved", true);
        run.put("exercisedCandidateCount", exercisedCandidateCount);
        run.put("unexercisedCandidateCount",
            CANDIDATE_COUNT - exercisedCandidateCount);
        run.set("formedCandidates", formedCandidates.deepCopy());
        run.set("aggregatePanelOutcomeCounts", object(aggregateCounts));
        run.put("candidatesWithCorrectnessRegression",
            candidatesWithRegression);
        run.set("physicalResourceUse", physicalResourceUse(
            formation.replayEvidence(),
            baselines,
            totalCandidateStates,
            totalCandidateGenerated,
            totalCandidatePathSteps));
        run.set("candidates", panelCandidates);
        run.put("formalProofStatus", "NOT_EVALUATED");
        run.put("externalNoveltyStatus", "NOT_EVALUATED");
        run.put("publicationAuthorized", false);
        addContentHash(run);

        Path output = arguments.output().toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(
            output,
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(run)
                + "\n",
            StandardCharsets.UTF_8);
        System.out.println("macroCandidatePanel=" + output);
        System.out.println("contentHash=" + text(run, "contentHash"));
        System.out.println("evaluatedCandidates=" + panelCandidates.size());
        System.out.println("exercisedCandidates=" + exercisedCandidateCount);
        System.out.println("aggregatePanelOutcomeCounts=" + aggregateCounts);
    }

    private static ObjectNode evaluation(
        TaskBinding task,
        BaselineBinding baseline,
        PairedEvaluation evaluation,
        String candidateId,
        boolean productionSelected
    ) {
        ObjectNode candidateRun = searchRun(evaluation.macroEnabled());
        ObjectNode delta = resourceDelta(
            evaluation.baseline(), evaluation.macroEnabled());
        if (productionSelected) {
            ObjectNode selectedTask = baseline.pairedUtilityTask();
            ObjectNode retainedRun = requireObjectField(
                selectedTask,
                "candidateEnabled",
                "selected paired candidate run");
            ObjectNode retainedDelta = requireObjectField(
                selectedTask,
                "resourceDelta",
                "selected paired resource delta");
            requireContentHash(retainedRun, "selected paired candidate run");
            requireContentHash(retainedDelta, "selected paired resource delta");
            require(candidateRun.equals(retainedRun),
                "selected panel run differs from paired-task utility");
            require(delta.equals(retainedDelta),
                "selected panel resource delta differs from paired-task utility");
            require(evaluation.outcome().name().equals(
                    text(selectedTask, "outcome")),
                "selected panel outcome differs from paired-task utility");
            require(evaluation.correctnessRegression()
                    == bool(
                        selectedTask,
                        "correctnessRegression",
                        "selected paired task"),
                "selected panel regression flag differs from paired-task utility");
        }

        ObjectNode result = JSON.createObjectNode();
        result.put("index", task.index());
        result.put("taskId", task.task().taskId());
        result.put("caseId", task.caseId());
        result.put("split", task.split());
        result.put("structuralCluster", task.structuralCluster());
        result.put("streamTaskContentHash", task.streamTaskContentHash());
        result.put("baselineContentHash", baseline.contentHash());
        result.put("enabledCandidateId", candidateId);
        result.put("outcome", evaluation.outcome().name());
        result.put("correctnessRegression",
            evaluation.correctnessRegression());
        result.set("candidateEnabled", candidateRun);
        result.set("resourceDelta", delta);
        result.put("productionSelectedRunParity", productionSelected);
        result.put("detail", evaluation.detail());
        addContentHash(result);
        return result;
    }

    private static ObjectNode candidateResourceUse(
        Map<String, BaselineBinding> baselines,
        int candidateStates,
        int candidateGenerated,
        int candidatePathSteps
    ) {
        int baselineStates = baselines.values().stream()
            .mapToInt(item -> item.run().expandedStates()).sum();
        int baselineGenerated = baselines.values().stream()
            .mapToInt(item -> item.run().generatedCandidates()).sum();
        int baselinePathSteps = baselines.values().stream()
            .mapToInt(item -> item.run().ruleIds().size()).sum();
        ObjectNode result = JSON.createObjectNode();
        result.put("baselineExecutionPolicy", BASELINE_POLICY);
        result.put("sharedBaselineExpandedStates", baselineStates);
        result.put("sharedBaselineGeneratedCandidates", baselineGenerated);
        result.put("sharedBaselinePathSteps", baselinePathSteps);
        result.put("candidateExpandedStates", candidateStates);
        result.put("candidateGeneratedCandidates", candidateGenerated);
        result.put("candidatePathSteps", candidatePathSteps);
        result.put("expandedStateSaving",
            baselineStates - candidateStates);
        result.put("generatedCandidateSaving",
            baselineGenerated - candidateGenerated);
        result.put("pathStepSaving",
            baselinePathSteps - candidatePathSteps);
        addContentHash(result);
        return result;
    }

    private static ObjectNode physicalResourceUse(
        List<ReplayEvidence> replayEvidence,
        Map<String, BaselineBinding> baselines,
        int candidateStates,
        int candidateGenerated,
        int candidatePathSteps
    ) {
        int formationStates = replayEvidence.stream()
            .mapToInt(ReplayEvidence::exploredStates).sum();
        int formationCandidates = replayEvidence.stream()
            .mapToInt(item -> item.actualRuleIds().size()).sum();
        int baselineStates = baselines.values().stream()
            .mapToInt(item -> item.run().expandedStates()).sum();
        int baselineGenerated = baselines.values().stream()
            .mapToInt(item -> item.run().generatedCandidates()).sum();
        int baselinePathSteps = baselines.values().stream()
            .mapToInt(item -> item.run().ruleIds().size()).sum();
        ObjectNode result = JSON.createObjectNode();
        result.put("policy",
            "FORMATION_ONCE_SHARED_BASELINES_ONCE_EACH_CANDIDATE_RUN_ONCE");
        result.put("formationStates", formationStates);
        result.put("formationCandidateEvaluations", formationCandidates);
        result.put("sharedBaselineExpandedStates", baselineStates);
        result.put("sharedBaselineGeneratedCandidates", baselineGenerated);
        result.put("sharedBaselinePathSteps", baselinePathSteps);
        result.put("candidatePanelExpandedStates", candidateStates);
        result.put("candidatePanelGeneratedCandidates", candidateGenerated);
        result.put("candidatePanelPathSteps", candidatePathSteps);
        result.put("totalPhysicalStates",
            formationStates + baselineStates + candidateStates);
        result.put("totalPhysicalCandidateEvaluations",
            formationCandidates + baselineGenerated + candidateGenerated);
        addContentHash(result);
        return result;
    }

    private static LinkedHashMap<String, BaselineBinding> sharedBaselines(
        ObjectNode pairedUtility,
        List<TaskBinding> tasks
    ) {
        ArrayNode utilityTasks = requireArray(
            pairedUtility, "tasks", "paired utility tasks");
        require(utilityTasks.size() == tasks.size(),
            "paired utility task count differs from the stream");
        LinkedHashMap<String, BaselineBinding> result = new LinkedHashMap<>();
        for (int index = 0; index < tasks.size(); index++) {
            TaskBinding task = tasks.get(index);
            ObjectNode utilityTask = requireObject(
                utilityTasks.get(index), "paired utility task");
            requireContentHash(utilityTask,
                "paired utility task " + task.task().taskId());
            require(integer(utilityTask, "index", "paired utility task")
                    == task.index(),
                "paired utility task order differs from the stream");
            require(text(utilityTask, "taskId").equals(task.task().taskId()),
                "paired utility task identity differs from the stream");
            require(text(utilityTask, "streamTaskContentHash").equals(
                    task.streamTaskContentHash()),
                "paired utility task binding differs from the stream");
            ObjectNode baseline = requireObjectField(
                utilityTask, "baseline", "paired utility baseline");
            requireContentHash(baseline,
                "paired utility baseline " + task.task().taskId());
            SearchRun run = readSearchRun(baseline);
            require(run.ruleIds().stream().noneMatch(ruleId ->
                    ruleId.startsWith("macro_candidate_independent_")),
                "verified baseline contains a learned candidate");
            BaselineBinding previous = result.put(
                task.task().taskId(),
                new BaselineBinding(
                    run, text(baseline, "contentHash"), utilityTask));
            require(previous == null,
                "duplicate shared baseline task identity");
        }
        return result;
    }

    private static SearchRun readSearchRun(ObjectNode value) {
        return new SearchRun(
            bool(value, "success", "search run"),
            textAllowEmpty(value, "reachedExpression", "search run"),
            stringValues(requireArray(value, "path", "search path")),
            stringValues(requireArray(value, "ruleIds", "search rules")),
            integer(value, "expandedStates", "search run"),
            integer(value, "generatedCandidates", "search run"),
            bool(value, "budgetExhausted", "search run"),
            text(value, "detail"));
    }

    private static void validateFormationInputs(
        ObjectNode corpus,
        ObjectNode profile
    ) {
        requireContentHash(corpus, "case corpus");
        requireContentHash(profile, "macro primitive profile");
        require(BENCHMARK.equals(text(corpus, "benchmarkId")),
            "case corpus benchmark identity changed");
        require("FROZEN_BEFORE_EVALUATED_EXECUTION".equals(
                text(corpus, "freezeStatus")),
            "case corpus is not pre-execution frozen");
        require("NOT_STARTED".equals(
                text(corpus, "executionStatusAtFreeze")),
            "case corpus execution status drift");
        require(PROFILE_ID.equals(text(profile, "profileId")),
            "macro primitive profile identity changed");
    }

    private static void validateExecutionInputs(
        ObjectNode corpus,
        ObjectNode profile,
        ObjectNode stream,
        ObjectNode pairedUtility
    ) {
        requireContentHash(stream, "downstream task stream");
        requireContentHash(pairedUtility, "paired task utility");
        require("regelsuche.downstream-task-stream/v1".equals(
                text(stream, "schema")),
            "unexpected downstream task stream schema");
        require("regelsuche.paired-task-utility/v1".equals(
                text(pairedUtility, "schema")),
            "unexpected paired task utility schema");
        require(text(stream, "sourceCaseCorpusContentHash").equals(
                text(corpus, "contentHash")),
            "stream/corpus binding drift");
        require(text(stream, "baselineInventoryContentHash").equals(
                text(profile, "contentHash")),
            "stream/profile binding drift");
        require(text(pairedUtility, "caseCorpusContentHash").equals(
                text(corpus, "contentHash")),
            "paired utility/corpus binding drift");
        require(text(pairedUtility, "baselineInventoryContentHash").equals(
                text(profile, "contentHash")),
            "paired utility/profile binding drift");
        require(text(pairedUtility, "downstreamTaskStreamContentHash").equals(
                text(stream, "contentHash")),
            "paired utility/stream binding drift");
        require(integer(
                pairedUtility,
                "enabledCandidateCount",
                "paired utility") == 1,
            "paired utility is not exact-one candidate");
        require(integer(
                pairedUtility,
                "correctnessRegressionCount",
                "paired utility") == 0,
            "paired utility selected run contains correctness regressions");
        require(!bool(
                pairedUtility,
                "publicationAuthorized",
                "paired utility"),
            "paired utility unexpectedly authorizes publication");
    }

    private static void validateFormationMatchesUtility(
        List<MacroCandidate> candidates,
        List<ReplayEvidence> replayEvidence,
        ObjectNode pairedUtility
    ) {
        ArrayNode retainedCandidates = requireArray(
            pairedUtility, "formedCandidates", "paired utility candidates");
        require(retainedCandidates.size() == candidates.size(),
            "panel formation count differs from paired utility");
        for (int index = 0; index < candidates.size(); index++) {
            MacroCandidate candidate = candidates.get(index);
            ObjectNode retained = requireObject(
                retainedCandidates.get(index), "paired utility candidate");
            requireContentHash(retained,
                "paired utility candidate " + candidate.macroId());
            require(candidate.macroId().equals(text(retained, "macroId")),
                "panel candidate identity differs from paired utility");
            require(candidate.operationSequence().equals(stringValues(
                    requireArray(
                        retained,
                        "operationSequence",
                        "candidate operation sequence"))),
                "panel candidate operation sequence differs");
            require(candidate.supportingTraceIds().equals(stringValues(
                    requireArray(
                        retained,
                        "supportingTraceIds",
                        "candidate supporting traces"))),
                "panel candidate TRAIN support differs");
            require(candidate.supportingTraceIds().size()
                    == integer(retained, "trainSupport", "candidate"),
                "panel candidate TRAIN support count differs");
            require(candidate.rule().leftPattern().equals(
                    text(retained, "leftPattern")),
                "panel candidate left pattern differs");
            require(candidate.rule().rightPattern().equals(
                    text(retained, "rightPattern")),
                "panel candidate right pattern differs");
            require(candidate.rule().parameterRelations().equals(stringValues(
                    requireArray(
                        retained,
                        "parameterRelations",
                        "candidate parameter relations"))),
                "panel candidate parameter relations differ");
            require(candidate.rule().assumptions().equals(stringValues(
                    requireArray(
                        retained,
                        "assumptions",
                        "candidate assumptions"))),
                "panel candidate assumptions differ");
            require(candidate.rule().proofStatus().name().equals(
                    text(retained, "proofStatus")),
                "panel candidate proof status differs");
            require(candidate.rule().knownRuleStatus().name().equals(
                    text(retained, "knownRuleStatus")),
                "panel candidate known-rule status differs");
            require(candidate.rule().supportingExamples()
                    == integer(retained, "supportingExamples", "candidate"),
                "panel candidate supporting-example count differs");
            require(candidate.rule().occurrenceCount()
                    == integer(retained, "occurrenceCount", "candidate"),
                "panel candidate occurrence count differs");
            require(Double.compare(
                    candidate.rule().confidenceScore(),
                    decimal(retained, "confidenceScore", "candidate")) == 0,
                "panel candidate confidence differs");
            require(candidate.rule().canonicalHash().equals(
                    text(retained, "canonicalHash")),
                "panel candidate canonical hash differs");
            require(candidate.validationEvidence().equals(
                    text(retained, "validationEvidence")),
                "panel candidate validation evidence differs");
            validateAtomicSteps(
                candidate.atomicSteps(),
                requireArray(retained, "atomicSteps", "candidate steps"));
        }

        ArrayNode retainedReplay = requireArray(
            pairedUtility,
            "formationReplayEvidence",
            "paired utility replay evidence");
        List<ReplayEvidence> sortedReplay = replayEvidence.stream()
            .sorted(Comparator.comparing(ReplayEvidence::traceId))
            .toList();
        require(retainedReplay.size() == sortedReplay.size(),
            "panel replay-evidence count differs");
        for (int index = 0; index < sortedReplay.size(); index++) {
            ReplayEvidence evidence = sortedReplay.get(index);
            ObjectNode retained = requireObject(
                retainedReplay.get(index), "paired utility replay evidence");
            requireContentHash(retained,
                "paired utility replay evidence " + evidence.traceId());
            require(evidence.traceId().equals(text(retained, "traceId")),
                "panel replay identity differs");
            require(evidence.reproduced()
                    == bool(retained, "reproduced", "replay evidence"),
                "panel replay result differs");
            require(evidence.exploredStates()
                    == integer(retained, "exploredStates", "replay evidence"),
                "panel replay state cost differs");
            require(evidence.actualRuleIds().equals(stringValues(
                    requireArray(retained, "actualRuleIds", "replay rules"))),
                "panel replay rules differ");
            require(evidence.assignedOperationIds().equals(stringValues(
                    requireArray(
                        retained,
                        "assignedOperationIds",
                        "replay operations"))),
                "panel replay operation assignments differ");
            require(evidence.compressedOperationIds().equals(stringValues(
                    requireArray(
                        retained,
                        "compressedOperationIds",
                        "replay compressed operations"))),
                "panel replay compressed operations differ");
            require(evidence.expressionPath().equals(stringValues(
                    requireArray(
                        retained,
                        "expressionPath",
                        "replay expression path"))),
                "panel replay path differs");
            require(evidence.detail().equals(text(retained, "detail")),
                "panel replay detail differs");
        }
    }

    private static void validateAtomicSteps(
        List<TransformationStep> current,
        ArrayNode retained
    ) {
        List<TransformationStep> sorted = current.stream()
            .sorted(Comparator.comparingInt(TransformationStep::index))
            .toList();
        require(retained.size() == sorted.size(),
            "panel candidate atomic-step count differs");
        for (int index = 0; index < sorted.size(); index++) {
            TransformationStep step = sorted.get(index);
            ObjectNode value = requireObject(
                retained.get(index), "paired utility atomic step");
            requireContentHash(value, "paired utility atomic step");
            require(step.index()
                    == integer(value, "index", "atomic step"),
                "panel candidate atomic-step index differs");
            require(step.beforeExpression().equals(
                    textAllowEmpty(value, "beforeExpression", "atomic step")),
                "panel candidate atomic-step source differs");
            require(step.afterExpression().equals(
                    textAllowEmpty(value, "afterExpression", "atomic step")),
                "panel candidate atomic-step target differs");
            require(step.ruleId().equals(text(value, "ruleId")),
                "panel candidate atomic-step rule differs");
            require(step.ruleKind().name().equals(text(value, "ruleKind")),
                "panel candidate atomic-step kind differs");
            require(step.scoreBefore()
                    == integer(value, "scoreBefore", "atomic step"),
                "panel candidate atomic-step source score differs");
            require(step.scoreAfter()
                    == integer(value, "scoreAfter", "atomic step"),
                "panel candidate atomic-step target score differs");
            require(step.equivalencePreserving()
                    == bool(
                        value,
                        "equivalencePreserving",
                        "atomic step"),
                "panel candidate atomic-step equivalence flag differs");
            require(step.explanation().equals(
                    textAllowEmpty(value, "explanation", "atomic step")),
                "panel candidate atomic-step explanation differs");
            require(step.assumptions().equals(stringValues(
                    requireArray(value, "assumptions", "atomic assumptions"))),
                "panel candidate atomic-step assumptions differ");
        }
    }

    private static LinkedHashMap<String, ObjectNode> retainedCandidates(
        ArrayNode candidates
    ) {
        LinkedHashMap<String, ObjectNode> result = new LinkedHashMap<>();
        for (JsonNode item : candidates) {
            ObjectNode candidate = requireObject(item, "formed candidate");
            String candidateId = text(candidate, "macroId");
            require(result.put(candidateId, candidate) == null,
                "duplicate formed candidate identity");
        }
        require(new ArrayList<>(result.keySet()).equals(
                result.keySet().stream().sorted().toList()),
            "formed candidates are not in stable macro-ID order");
        return result;
    }

    private static List<TaskBinding> streamTasks(ObjectNode stream) {
        List<TaskBinding> result = new ArrayList<>();
        int expectedIndex = 1;
        for (JsonNode item : requireArray(
                stream, "tasks", "downstream stream tasks")) {
            ObjectNode task = requireObject(item, "downstream stream task");
            requireContentHash(task,
                "downstream stream task " + text(task, "taskId"));
            int index = integer(task, "index", "downstream stream task");
            require(index == expectedIndex,
                "downstream task order changed at index " + expectedIndex);
            expectedIndex++;
            ObjectNode budget = requireObjectField(
                task, "searchBudget", "downstream task budget");
            result.add(new TaskBinding(
                index,
                text(task, "caseId"),
                text(task, "split"),
                text(task, "structuralCluster"),
                text(task, "contentHash"),
                new EvaluationTask(
                    text(task, "taskId"),
                    text(task, "source"),
                    text(task, "target"),
                    stringValues(requireArray(
                        task,
                        "assumptions",
                        "downstream task assumptions")),
                    integer(budget, "maxDepth", "downstream task budget"),
                    integer(
                        budget,
                        "maxExpandedStates",
                        "downstream task budget"))));
        }
        require(result.size() == TASK_COUNT,
            "frozen downstream task count changed");
        return List.copyOf(result);
    }

    private static List<ReplayTrace> trainTraces(List<ObjectNode> cases) {
        List<ReplayTrace> result = new ArrayList<>();
        for (ObjectNode benchmarkCase : cases) {
            if (!"TRAIN".equals(text(benchmarkCase, "split"))) {
                continue;
            }
            ObjectNode formation = requireObjectField(
                benchmarkCase,
                "formationInput",
                "macro TRAIN formationInput");
            require(!bool(
                    formation,
                    "heldOutTargetsVisible",
                    "macro TRAIN formation"),
                "macro TRAIN formation exposes held-out targets");
            require(PROFILE_ID.equals(
                    text(formation, "primitiveInventoryProfile")),
                "macro TRAIN case uses an unexpected primitive profile");
            for (JsonNode item : requireArray(
                    formation, "replayTraces", "macro replay traces")) {
                ObjectNode trace = requireObject(item, "macro replay trace");
                result.add(new ReplayTrace(
                    text(trace, "traceId"),
                    text(trace, "source"),
                    text(trace, "target"),
                    stringValues(requireArray(
                        trace,
                        "primitiveSteps",
                        "trace primitive steps")),
                    stringValues(requireArray(
                        trace,
                        "assumptions",
                        "trace assumptions"))));
            }
        }
        result.sort(Comparator.comparing(ReplayTrace::traceId));
        require(result.size() == 4,
            "frozen macro TRAIN replay count changed");
        return List.copyOf(result);
    }

    private static Map<String, List<String>> operationRules(
        ObjectNode profile
    ) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        for (JsonNode item : requireArray(
                profile, "operations", "macro profile operations")) {
            ObjectNode operation = requireObject(item, "macro operation");
            result.put(
                text(operation, "operationId"),
                stringValues(requireArray(
                    operation,
                    "implementationRuleIds",
                    "macro operation implementation rules")));
        }
        require(result.size() == 6,
            "macro primitive operation count changed");
        return Map.copyOf(result);
    }

    private static List<ObjectNode> macroCases(ObjectNode corpus) {
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode item : requireArray(corpus, "cases", "case corpus")) {
            ObjectNode benchmarkCase = requireObject(item, "benchmark case");
            if (!CHALLENGE.equals(text(benchmarkCase, "challengeId"))) {
                continue;
            }
            requireContentHash(benchmarkCase,
                "macro case " + text(benchmarkCase, "caseId"));
            validateExposure(benchmarkCase);
            result.add(benchmarkCase);
        }
        result.sort(Comparator.comparing(item -> text(item, "caseId")));
        require(result.stream()
                .map(item -> text(item, "caseId"))
                .toList()
                .equals(List.of(
                    "case-13", "case-14", "case-15",
                    "case-16", "case-17", "case-18")),
            "macro case identities changed");
        return List.copyOf(result);
    }

    private static void validateExposure(ObjectNode benchmarkCase) {
        String caseId = text(benchmarkCase, "caseId");
        String split = text(benchmarkCase, "split");
        ObjectNode policy = requireObjectField(
            benchmarkCase, "exposurePolicy", "macro exposure policy");
        List<String> mayRead = stringValues(requireArray(
            policy,
            "candidateFormationMayRead",
            "formation readable inputs"));
        List<String> mustNotRead = stringValues(requireArray(
            policy,
            "candidateFormationMustNotRead",
            "formation prohibited inputs"));
        require(mustNotRead.equals(List.of("evaluationInput")),
            "case " + caseId + " does not prohibit evaluation input");
        if ("TRAIN".equals(split)) {
            require(mayRead.equals(List.of("formationInput")),
                "TRAIN macro formation surface changed: " + caseId);
            require(benchmarkCase.path("formationInput").isObject(),
                "TRAIN macro case has no formation payload: " + caseId);
        } else {
            require(mayRead.isEmpty(),
                "held-out macro case exposes formation data: " + caseId);
            require(benchmarkCase.path("formationInput").isNull(),
                "held-out macro case has formation payload: " + caseId);
        }
    }

    private static ObjectNode searchRun(SearchRun run) {
        ObjectNode result = JSON.createObjectNode();
        result.put("success", run.success());
        result.put("reachedExpression", run.reachedExpression());
        result.set("path", strings(run.path()));
        result.set("ruleIds", strings(run.ruleIds()));
        result.put("expandedStates", run.expandedStates());
        result.put("generatedCandidates", run.generatedCandidates());
        result.put("budgetExhausted", run.budgetExhausted());
        result.put("detail", run.detail());
        addContentHash(result);
        return result;
    }

    private static ObjectNode resourceDelta(
        SearchRun baseline,
        SearchRun candidate
    ) {
        ObjectNode result = JSON.createObjectNode();
        result.put("expandedStateSaving",
            baseline.expandedStates() - candidate.expandedStates());
        result.put("generatedCandidateSaving",
            baseline.generatedCandidates() - candidate.generatedCandidates());
        result.put("pathStepSaving",
            baseline.ruleIds().size() - candidate.ruleIds().size());
        addContentHash(result);
        return result;
    }

    private static LinkedHashMap<String, Integer> emptyOutcomeCounts() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (UtilityOutcome outcome : UtilityOutcome.values()) {
            result.put(outcome.name(), 0);
        }
        return result;
    }

    private static ObjectNode readObject(Path path) throws IOException {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path),
            "expected regular non-symbolic JSON file: " + path);
        return requireObject(
            JSON.readTree(Files.readString(path, StandardCharsets.UTF_8)),
            path.toString());
    }

    private static void requireContentHash(
        ObjectNode value,
        String context
    ) {
        String retained = text(value, "contentHash");
        ObjectNode material = value.deepCopy();
        material.remove("contentHash");
        String expected = semanticHash(material);
        require(retained.equals(expected),
            context + " contentHash mismatch: " + retained + " != "
                + expected);
    }

    private static void addContentHash(ObjectNode value) {
        require(!value.has("contentHash"), "contentHash already present");
        value.put("contentHash", semanticHash(value));
    }

    private static String semanticHash(JsonNode value) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    canonicalJson(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String canonicalJson(JsonNode value) {
        try {
            if (value.isObject()) {
                TreeSet<String> fields = new TreeSet<>();
                value.fieldNames().forEachRemaining(fields::add);
                StringBuilder result = new StringBuilder("{");
                int index = 0;
                for (String field : fields) {
                    if (index++ > 0) {
                        result.append(',');
                    }
                    result.append(JSON.writeValueAsString(field))
                        .append(':')
                        .append(canonicalJson(value.get(field)));
                }
                return result.append('}').toString();
            }
            if (value.isArray()) {
                StringBuilder result = new StringBuilder("[");
                for (int index = 0; index < value.size(); index++) {
                    if (index > 0) {
                        result.append(',');
                    }
                    result.append(canonicalJson(value.get(index)));
                }
                return result.append(']').toString();
            }
            return JSON.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "cannot canonicalize JSON", exception);
        }
    }

    private static ObjectNode object(Map<String, ?> values) {
        return JSON.valueToTree(new LinkedHashMap<>(values));
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode result = JSON.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    private static List<String> stringValues(ArrayNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            require(value.isTextual() && !value.asText().isBlank(),
                "expected nonblank string array item");
            result.add(value.asText());
        }
        return List.copyOf(result);
    }

    private static ObjectNode requireObject(
        JsonNode value,
        String context
    ) {
        require(value != null && value.isObject(),
            context + " is not an object");
        return (ObjectNode) value;
    }

    private static ObjectNode requireObjectField(
        JsonNode value,
        String field,
        String context
    ) {
        return requireObject(value.get(field), context);
    }

    private static ArrayNode requireArray(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.isArray(),
            context + " is not an array");
        return (ArrayNode) child;
    }

    private static String text(JsonNode value, String field) {
        Objects.requireNonNull(value, "value");
        JsonNode child = value.get(field);
        require(child != null && child.isTextual()
                && !child.asText().isBlank(),
            "missing textual field " + field);
        return child.asText();
    }

    private static String textAllowEmpty(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.isTextual(),
            context + " has no textual field " + field);
        return child.asText();
    }

    private static int integer(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.canConvertToInt(),
            context + " has no integer field " + field);
        return child.intValue();
    }

    private static double decimal(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.isNumber(),
            context + " has no numeric field " + field);
        return child.doubleValue();
    }

    private static boolean bool(
        JsonNode value,
        String field,
        String context
    ) {
        JsonNode child = value.get(field);
        require(child != null && child.isBoolean(),
            context + " has no boolean field " + field);
        return child.booleanValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record TaskBinding(
        int index,
        String caseId,
        String split,
        String structuralCluster,
        String streamTaskContentHash,
        EvaluationTask task
    ) {
    }

    private record BaselineBinding(
        SearchRun run,
        String contentHash,
        ObjectNode pairedUtilityTask
    ) {
        private BaselineBinding {
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(contentHash, "contentHash");
            Objects.requireNonNull(pairedUtilityTask, "pairedUtilityTask");
        }
    }

    private record Arguments(
        Path corpus,
        Path profile,
        Path downstreamStream,
        Path pairedUtility,
        Path output,
        String repositoryRevision
    ) {
        private static final Set<String> EXPECTED = Set.of(
            "--corpus",
            "--profile",
            "--downstream-stream",
            "--paired-utility",
            "--output",
            "--repository-revision");

        private static Arguments parse(String[] args) {
            if (args.length % 2 != 0) {
                throw new IllegalArgumentException(
                    "expected --key value arguments");
            }
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                String key = args[index];
                if (!key.startsWith("--")) {
                    throw new IllegalArgumentException(
                        "expected --key value arguments");
                }
                if (values.put(key, args[index + 1]) != null) {
                    throw new IllegalArgumentException(
                        "duplicate argument " + key);
                }
            }
            if (!values.keySet().equals(EXPECTED)) {
                throw new IllegalArgumentException(
                    "argument allowlist drift: " + values.keySet());
            }
            return new Arguments(
                Path.of(required(values, "--corpus")),
                Path.of(required(values, "--profile")),
                Path.of(required(values, "--downstream-stream")),
                Path.of(required(values, "--paired-utility")),
                Path.of(required(values, "--output")),
                required(values, "--repository-revision"));
        }

        private static String required(
            Map<String, String> values,
            String key
        ) {
            String value = values.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                    "missing required argument " + key);
            }
            return value;
        }
    }
}
