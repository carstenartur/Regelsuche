package de.regelsuche.search.strategy;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.search.program.BudgetedRewriteProgramExecution.PathBudget;
import de.regelsuche.search.program.RewriteCandidate;
import de.regelsuche.search.program.RewriteExecution;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Metrics;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Problem;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.Result;
import de.regelsuche.search.strategy.WorkBudgetBestFirstSearchStrategy.State;
import de.regelsuche.transform.ExecutionWork;
import de.regelsuche.transform.Transformation;
import de.regelsuche.transform.TransformationProvenance;
import de.regelsuche.transform.TransformationWorkMetrics;
import java.util.List;
import java.util.Objects;

/** Canonical observation and replay comparison; JSON never creates executable evidence. */
public final class WorkSearchReplay {
    public static final String SCHEMA = "regelsuche.work-search-replay/v1";

    private WorkSearchReplay() {}

    /**
     * Re-executes trusted sources and compares every retained work/provenance field.
     * The caller must reconstruct theory sources through their full verifier
     * pipeline; this method does not turn serialized evidence into authority.
     */
    public static Result verify(String expectedCanonicalJson, Problem independentlyVerifiedProblem) {
        Objects.requireNonNull(expectedCanonicalJson, "expectedCanonicalJson");
        var replay = new WorkBudgetBestFirstSearchStrategy().search(independentlyVerifiedProblem);
        if (!replay.toCanonicalJson().equals(expectedCanonicalJson)) {
            throw new IllegalArgumentException("search replay differs in state, provenance, work or decisions");
        }
        return replay;
    }

    public static String toCanonicalJson(Result result) {
        var json = new JsonWriter().beginObject().property("schema", SCHEMA)
            .property("workRevision", result.metrics().workRevision().schema())
            .object("configuration", item -> writeConfiguration(item, result))
            .property("status", result.status().name())
            .property("expansionsComplete", result.expansionsComplete())
            .object("metrics", item -> writeMetrics(item, result.metrics()))
            .array("exploredStates", array -> result.exploredStates().forEach(
                state -> array.objectValue(item -> writeState(item, state))))
            .object("bestState", item -> writeState(item, result.bestState()));
        if (result.reachedState() == null) json.nullProperty("reachedState");
        else json.object("reachedState", item -> writeState(item, result.reachedState()));
        json.array("expansions", array -> result.expansions().forEach(call -> array.objectValue(item -> {
            item.object("source", state -> writeState(state, call.source()));
            item.object("execution", execution -> writeExecution(execution, call.execution()));
        })));
        json.array("candidateDecisions", array -> result.candidateDecisions().forEach(decision -> array.objectValue(item -> {
            item.object("source", state -> writeState(state, decision.source())).property("outcome", decision.outcome().name());
            writePath(item, decision.source().expression(), List.of(decision.transformation()));
        })));
        return json.endObject().toString();
    }

    private static void writeConfiguration(JsonWriter json, Result result) {
        var configuration = result.configuration();
        var budget = configuration.budget();
        json.property("inputExpression", configuration.inputExpression()).property("targetExpression", configuration.targetExpression())
            .property("workRevision", configuration.workRevision().schema())
            .property("maxPrimitiveSteps", budget.maxPrimitiveSteps()).property("maxExactTheoryWorkUnits", budget.maxExactTheoryWorkUnits())
            .property("maxExploredStates", budget.maxExploredStates()).property("maxCandidatesPerState", budget.maxCandidatesPerState())
            .property("maxExpandingSteps", budget.maxExpandingSteps()).property("maxWorkUnits", budget.maxWorkUnits());
    }

    private static void writeState(JsonWriter json, State state) {
        json.property("expression", state.expression()).property("edgeDepth", state.edgeDepth())
            .property("primitiveDepth", state.primitiveDepth()).property("canonicalHash", state.canonicalHash())
            .object("score", item -> item.property("stringLength", state.score().stringLength())
                .property("astNodeCount", state.score().astNodeCount()).property("operatorCount", state.score().operatorCount())
                .property("nestingDepth", state.score().nestingDepth()).property("recognizedPatternBonus", state.score().recognizedPatternBonus()))
            .property("expandingSteps", state.expandingSteps())
            .stringArray("path", state.path()).stringArray("appliedRuleIds", state.appliedRuleIds())
            .stringArray("primitiveRuleIds", state.primitiveRuleIds())
            .stringArray("applicationKeys", state.appliedRuleApplications().stream().sorted().toList())
            .stringArray("assumptions", state.assumptions())
            .object("executionWork", item -> writeWork(item, state.executionWork()));
        writePath(json, state.path().getFirst(), state.transformations());
    }

    private static void writePath(JsonWriter json, String input, List<Transformation> steps) {
        if (steps.isEmpty()) json.nullProperty("provenance");
        else json.property("provenance", new TransformationProvenance.Sequence(input, steps).toCanonicalJson());
    }

    private static void writeCandidate(JsonWriter json, RewriteCandidate candidate) {
        json.property("originNodeId", candidate.originNodeId()).property("input", candidate.inputExpression())
            .property("output", candidate.outputExpression());
        writePath(json, candidate.inputExpression(), candidate.steps());
    }

    private static void writeExecution(JsonWriter json, RewriteExecution execution) {
        json.property("workRevision", execution.workRevision()).property("complete", execution.complete())
            .object("work", item -> writeTransformationWork(item, execution.workMetrics()));
        if (execution.pathBudget() == null) json.nullProperty("pathBudget");
        else json.object("pathBudget", item -> writeBudget(item, execution.pathBudget()));
        json.array("candidates", array -> execution.candidates().forEach(candidate ->
            array.objectValue(item -> writeCandidate(item, candidate))));
        json.array("sourceObservations", array -> execution.sourceObservations().forEach(observation ->
            array.objectValue(item -> {
                item.property("admitted", observation.admitted())
                    .object("availableBudget", budget -> writeBudget(budget, observation.availableBudget()))
                    .object("candidate", candidate -> writeCandidate(candidate, observation.candidate()));
            })));
    }

    private static void writeBudget(JsonWriter json, PathBudget budget) {
        json.property("primitiveRewriteUnits", budget.primitiveRewriteUnits())
            .property("exactTheoryWorkUnits", budget.exactTheoryWorkUnits());
    }

    private static void writeWork(JsonWriter json, ExecutionWork work) {
        json.property("primitiveRewrites", work.primitiveRewrites()).property("exactTheorySteps", work.exactTheorySteps())
            .property("exactTheoryWorkUnits", work.exactTheoryWorkUnits()).property("canonicalWorkUnits", work.canonicalWorkUnits());
    }

    private static void writeMetrics(JsonWriter json, Metrics metrics) {
        json.property("exploredStates", metrics.exploredStates()).property("expandedStates", metrics.expandedStates())
            .property("generatedTransformations", metrics.generatedTransformations()).property("enqueuedStates", metrics.enqueuedStates())
            .property("duplicatePrunes", metrics.duplicatePrunes()).property("repeatedApplicationPrunes", metrics.repeatedApplicationPrunes())
            .property("sameExpressionPrunes", metrics.sameExpressionPrunes()).property("expansionBudgetPrunes", metrics.expansionBudgetPrunes())
            .property("primitiveBudgetPrunes", metrics.primitiveBudgetPrunes()).property("candidateBudgetPrunes", metrics.candidateBudgetPrunes())
            .property("statesWithoutTransformations", metrics.statesWithoutTransformations()).property("engineBatches", metrics.engineBatches())
            .property("pathWorkBudgetPrunes", metrics.pathWorkBudgetPrunes()).property("frontierAdmissionChecks", metrics.frontierAdmissionChecks())
            .property("primitiveStepBudget", metrics.primitiveStepBudget()).property("exactTheoryWorkBudget", metrics.exactTheoryWorkBudget())
            .property("workUnitBudget", metrics.workUnitBudget()).property("exactPathAuditReserve", metrics.exactPathAuditReserve())
            .property("outerSearchWorkUnits", metrics.outerSearchWorkUnits()).property("chargedSearchWorkUnits", metrics.chargedSearchWorkUnits())
            .object("transformationWork", item -> writeTransformationWork(item, metrics.transformationWork()));
    }

    private static void writeTransformationWork(JsonWriter json, TransformationWorkMetrics work) {
        // Preserve frozen historical bytes when no delegated source ran.
        if (work.delegatedMechanicalWorkUnits() != 0) {
            json.property("delegatedMechanicalWorkUnits", work.delegatedMechanicalWorkUnits());
        }
        json.property("engineInvocations", work.engineInvocations()).property("programNodeVisits", work.programNodeVisits())
            .property("sourceInvocations", work.sourceInvocations()).property("sourceCandidates", work.sourceCandidates())
            .property("composedCandidates", work.composedCandidates()).property("requirementEvaluations", work.requirementEvaluations())
            .property("requirementRejections", work.requirementRejections()).property("priorityCandidatesOrdered", work.priorityCandidatesOrdered())
            .property("prunedCandidates", work.prunedCandidates()).property("repeatIterations", work.repeatIterations())
            .property("repeatEndpoints", work.repeatEndpoints()).property("alternativeSelections", work.alternativeSelections())
            .property("alternativesSkipped", work.alternativesSkipped()).property("duplicateCandidatesDropped", work.duplicateCandidatesDropped())
            .object("candidateWork", item -> writeWork(item, work.candidateWork()));
    }
}
