package de.regelsuche.benchmark;

import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayEvidence;
import de.regelsuche.benchmark.CandidateIndependentMacroReplayAdapter.ReplayTrace;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.inventory.ReusableRule;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Forms reusable macros from frozen TRAIN replays and measures their paired
 * held-out utility with the production best-first search.
 *
 * <p>The formation surface contains replay traces only. Held-out tasks are
 * accepted solely by {@link #evaluate(EvaluationTask, FormationResult)} after
 * formation has completed. Baseline and macro-enabled runs use identical
 * source, target, assumptions, primitive inventory, search strategy and
 * budgets; the macro run differs only by the formed macro inventory.</p>
 */
public final class CandidateIndependentReusableMacroAdapter {
    private final CandidateIndependentMacroFormation formation;
    private final CandidateIndependentMacroUtilityEvaluator utility;

    public CandidateIndependentReusableMacroAdapter(
        Map<String, List<String>> operationRuleIds
    ) {
        formation = new CandidateIndependentMacroFormation(operationRuleIds);
        utility = new CandidateIndependentMacroUtilityEvaluator(operationRuleIds);
    }

    public FormationResult form(List<ReplayTrace> traces) {
        return formation.form(traces);
    }

    public PairedEvaluation evaluate(
        EvaluationTask task,
        FormationResult formationResult
    ) {
        return utility.evaluate(task, formationResult);
    }

    public enum FormationStatus {
        SELECTED,
        REPLAY_NOT_REPRODUCED,
        GENERALIZATION_REJECTED
    }

    public enum UtilityOutcome {
        IMPROVED,
        REACHABILITY_GAIN,
        NO_IMPROVEMENT,
        NO_RESULT,
        CORRECTNESS_REGRESSION,
        CANDIDATE_NOT_FORMED
    }

    public record MacroCandidate(
        String macroId,
        List<String> operationSequence,
        List<String> supportingTraceIds,
        ReusableRule rule,
        List<TransformationStep> atomicSteps,
        String validationEvidence
    ) {
        public MacroCandidate {
            macroId = requireText(macroId, "macroId");
            operationSequence = immutableStrings(
                operationSequence, "operationSequence");
            supportingTraceIds = immutableStrings(
                supportingTraceIds, "supportingTraceIds");
            Objects.requireNonNull(rule, "rule");
            atomicSteps = atomicSteps == null
                ? List.of() : List.copyOf(atomicSteps);
            validationEvidence = requireText(
                validationEvidence, "validationEvidence");
        }
    }

    public record FormationResult(
        FormationStatus status,
        List<MacroCandidate> macros,
        List<ReplayEvidence> replayEvidence,
        String detail
    ) {
        public FormationResult {
            Objects.requireNonNull(status, "status");
            macros = macros == null ? List.of() : List.copyOf(macros);
            replayEvidence = replayEvidence == null
                ? List.of() : List.copyOf(replayEvidence);
            detail = requireText(detail, "detail");
            if (status == FormationStatus.SELECTED && macros.isEmpty()) {
                throw new IllegalArgumentException(
                    "selected formation requires macros");
            }
        }
    }

    public record EvaluationTask(
        String taskId,
        String source,
        String target,
        List<String> assumptions,
        int maxDepth,
        int maxExpandedStates
    ) {
        public EvaluationTask {
            taskId = requireText(taskId, "taskId");
            source = requireText(source, "source");
            target = requireText(target, "target");
            assumptions = assumptions == null
                ? List.of() : List.copyOf(assumptions);
            if (maxDepth < 0 || maxExpandedStates < 1) {
                throw new IllegalArgumentException(
                    "paired search budgets are invalid");
            }
        }
    }

    public record SearchRun(
        boolean success,
        String reachedExpression,
        List<String> path,
        List<String> ruleIds,
        int expandedStates,
        int generatedCandidates,
        boolean budgetExhausted,
        String detail
    ) {
        public SearchRun {
            reachedExpression = reachedExpression == null
                ? "" : reachedExpression;
            path = path == null ? List.of() : List.copyOf(path);
            ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
            if (expandedStates < 0 || generatedCandidates < 0) {
                throw new IllegalArgumentException(
                    "search resource values must not be negative");
            }
            detail = requireText(detail, "detail");
        }

        static SearchRun notRun(EvaluationTask task, String detail) {
            return new SearchRun(
                false, "", List.of(task.source()), List.of(),
                0, 0, false, detail);
        }
    }

    public record PairedEvaluation(
        String taskId,
        UtilityOutcome outcome,
        SearchRun baseline,
        SearchRun macroEnabled,
        boolean correctnessRegression,
        String detail
    ) {
        public PairedEvaluation {
            taskId = requireText(taskId, "taskId");
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(baseline, "baseline");
            Objects.requireNonNull(macroEnabled, "macroEnabled");
            detail = requireText(detail, "detail");
        }
    }

    static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static List<String> immutableStrings(
        List<String> values,
        String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(
                name + " must not contain blank values");
        }
        return List.copyOf(values);
    }
}
