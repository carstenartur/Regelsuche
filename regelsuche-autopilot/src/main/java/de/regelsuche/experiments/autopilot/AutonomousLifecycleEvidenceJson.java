package de.regelsuche.experiments.autopilot;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.mining.HypothesisCandidate;
import de.regelsuche.mining.OpenTargetConjectureEvaluator.EvaluationReport;
import de.regelsuche.mining.OpenTargetConjectureNoveltyChecker.NoveltyReport;
import java.util.Comparator;
import java.util.Map;

/** Canonical rendering for production evidence whose domain records have no JSON writer. */
final class AutonomousLifecycleEvidenceJson {
    private AutonomousLifecycleEvidenceJson() {
    }

    static String validation(EvaluationReport report) {
        return new JsonWriter().beginObject()
            .property("schema", report.schema())
            .property("conjectureId", report.conjectureId())
            .property("status", report.status().name())
            .property("compilationStatus", report.compilationStatus())
            .property("dynamicRuleId", report.dynamicRuleId())
            .property("provenanceHash", report.provenanceHash())
            .property("configuredPositiveHoldouts", report.configuredPositiveHoldouts())
            .property("executedPositiveHoldouts", report.executedPositiveHoldouts())
            .property("skippedPositiveHoldouts", report.skippedPositiveHoldouts())
            .property("configuredNegativeHoldouts", report.configuredNegativeHoldouts())
            .property("executedNegativeHoldouts", report.executedNegativeHoldouts())
            .property("skippedNegativeHoldouts", report.skippedNegativeHoldouts())
            .array("positiveResults", array -> report.positiveResults().forEach(item ->
                array.objectValue(object -> object
                    .property("id", item.id())
                    .property("candidateCount", item.candidateCount())
                    .property("equivalentCandidate", item.equivalentCandidate())
                    .stringArray("candidateExpressions", item.candidateExpressions()))))
            .array("negativeResults", array -> report.negativeResults().forEach(item ->
                array.objectValue(object -> object
                    .property("id", item.id())
                    .property("candidateCount", item.candidateCount())
                    .property("noApplication", item.noApplication())
                    .stringArray("candidateExpressions", item.candidateExpressions()))))
            .property("holdoutsComplete", report.holdoutsComplete())
            .property("allHoldoutsPassed", report.allHoldoutsPassed())
            .property("acceptedForProof", report.acceptedForProof())
            .stringArray("blockers", report.blockers())
            .property("proofStatus", report.proofStatus())
            .property("noveltyStatus", report.noveltyStatus())
            .endObject()
            .toString();
    }

    static String counterexample(String conjectureId, EvaluationReport report) {
        var evidence = report.counterexample();
        return new JsonWriter().beginObject()
            .property("schema", "regelsuche.open-target-counterexample-evidence/v1")
            .property("conjectureId", conjectureId)
            .property("status", evidence.status())
            .stringArray("attemptedSources", evidence.attemptedSources())
            .stringArray("inferredAssumptions", evidence.inferredAssumptions())
            .stringArray("assignments", evidence.assignments())
            .property("leftValue", evidence.leftValue())
            .property("rightValue", evidence.rightValue())
            .property("explanation", evidence.explanation())
            .endObject()
            .toString();
    }

    static String novelty(NoveltyReport report) {
        return new JsonWriter().beginObject()
            .property("schema", report.schema())
            .property("conjectureId", report.conjectureId())
            .property("status", report.status().name())
            .property("exactSignatureHash", report.exactSignatureHash())
            .property("alphaSignatureHash", report.alphaSignatureHash())
            .property("checkedActiveRules", report.checkedActiveRules())
            .property("checkedPriorCandidates", report.checkedPriorCandidates())
            .array("matches", array -> report.matches().forEach(match ->
                array.objectValue(object -> object
                    .property("source", match.source())
                    .property("candidateId", match.candidateId())
                    .property("relation", match.relation().name()))))
            .property("externalNoveltyStatus", report.externalNoveltyStatus())
            .property("explanation", report.explanation())
            .endObject()
            .toString();
    }

    static String lifecycleCandidate(HypothesisCandidate candidate) {
        return new JsonWriter().beginObject()
            .property("schema", "regelsuche.open-target-lifecycle-candidate/v1")
            .property("id", candidate.id())
            .property("leftPattern", candidate.leftPattern())
            .property("rightPattern", candidate.rightPattern())
            .stringArray("supportingPaths", candidate.supportingPaths())
            .array("supportingExpressions", array ->
                candidate.supportingExpressions().stream()
                    .sorted(Comparator
                        .comparing(HypothesisCandidate.ExpressionPair::left)
                        .thenComparing(HypothesisCandidate.ExpressionPair::right))
                    .forEach(pair -> array.objectValue(object -> object
                        .property("left", pair.left())
                        .property("right", pair.right()))))
            .stringArray("assumptions", candidate.assumptions())
            .property("noveltyScore", candidate.noveltyScore())
            .property("proofStatus", candidate.proofStatus().name())
            .property("counterexampleStatus",
                Boolean.TRUE.equals(candidate.counterexampleStatus()))
            .property("counterexampleSearchStatus",
                candidate.counterexampleSearchStatus().name())
            .stringArray("counterexampleAttemptedSources",
                candidate.counterexampleAttemptedSources())
            .property("counterexampleExplanation",
                candidate.counterexampleExplanation())
            .stringArray("parameterRelations", candidate.parameterRelations())
            .object("expressionPlaceholders", object ->
                candidate.expressionPlaceholders().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> object.stringArray(
                        entry.getKey(), entry.getValue())))
            .property("createdAt", candidate.createdAt().toString())
            .property("promotionStatus", "NOT_EVALUATED")
            .property("publicEvidenceStatus", "NOT_EVALUATED")
            .endObject()
            .toString();
    }
}
