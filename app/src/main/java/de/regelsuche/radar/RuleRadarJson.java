package de.regelsuche.radar;

import de.regelsuche.json.JsonWriter;
import java.util.Map;

import static de.regelsuche.radar.AstRuleRadar.ApplicableMove;
import static de.regelsuche.radar.AstRuleRadar.AstNode;
import static de.regelsuche.radar.AstRuleRadar.AtomicStep;
import static de.regelsuche.radar.AstRuleRadar.Binding;
import static de.regelsuche.radar.AstRuleRadar.Context;
import static de.regelsuche.radar.AstRuleRadar.Diagnostic;
import static de.regelsuche.radar.AstRuleRadar.MacroEvidence;
import static de.regelsuche.radar.AstRuleRadar.SearchEdge;
import static de.regelsuche.radar.AstRuleRadar.SearchEvent;
import static de.regelsuche.radar.AstRuleRadar.SearchResult;
import static de.regelsuche.radar.AstRuleRadar.SearchState;
import static de.regelsuche.radar.AstRuleRadar.Snapshot;

/** Deterministic JSON rendering for the rule-radar HTTP surface. */
public final class RuleRadarJson {
    private RuleRadarJson() {
    }

    public static String snapshot(Snapshot snapshot) {
        JsonWriter writer = new JsonWriter().beginObject();
        writeSnapshot(writer, snapshot);
        return writer.endObject().toString();
    }

    public static String searchResult(SearchResult result) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("schema", result.schema());
        writer.property("startExpression", result.startExpression());
        writer.property("targetExpression", result.targetExpression());
        writer.property("targetReached", result.targetReached());
        writer.property("terminalStateId", result.terminalStateId());
        writer.property("exploredStateCount", result.exploredStateCount());
        writer.property("generatedCandidateCount", result.generatedCandidateCount());
        writer.array("states", array -> result.states().forEach(state -> array.objectValue(item -> writeState(item, state))));
        writer.array("edges", array -> result.edges().forEach(edge -> array.objectValue(item -> writeEdge(item, edge))));
        writer.array("events", array -> result.events().forEach(event -> array.objectValue(item -> writeEvent(item, event))));
        writer.object("finalOutcomeByCandidateId", object -> result.finalOutcomeByCandidateId().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> object.property(entry.getKey(), entry.getValue().name())));
        writer.array("diagnostics", array -> result.diagnostics().forEach(diagnostic ->
            array.objectValue(item -> writeDiagnostic(item, diagnostic))));
        return writer.endObject().toString();
    }

    public static String applyResult(
        ApplicableMove applied,
        Snapshot refreshed,
        boolean expressionChanged
    ) {
        JsonWriter writer = new JsonWriter().beginObject();
        writer.property("candidateId", applied.candidateId());
        writer.property("pathKey", applied.pathKey());
        writer.property("ruleId", applied.ruleId());
        writer.property("origin", applied.origin().name());
        writer.property("expressionBefore", applied.expressionBefore());
        writer.property("expressionAfter", applied.expressionAfter());
        writer.property("expressionChanged", expressionChanged);
        writer.object("candidate", object -> writeCandidate(object, applied));
        writer.object("inspection", object -> writeSnapshot(object, refreshed));
        return writer.endObject().toString();
    }

    private static void writeSnapshot(JsonWriter writer, Snapshot snapshot) {
        writer.property("schema", snapshot.schema());
        writer.property("expression", snapshot.expression());
        writer.property("canonicalExpression", snapshot.canonicalExpression());
        writer.property("valid", snapshot.valid());
        writer.object("context", object -> writeContext(object, snapshot.context()));
        writer.array("nodes", array -> snapshot.nodes().forEach(node -> array.objectValue(item -> writeNode(item, node))));
        writer.array("candidates", array -> snapshot.candidates().forEach(candidate ->
            array.objectValue(item -> writeCandidate(item, candidate))));
        writer.object("truncation", object -> {
            object.property("truncated", snapshot.truncation().truncated());
            object.property("generatedCandidateCount", snapshot.truncation().generatedCandidateCount());
            object.property("returnedCandidateCount", snapshot.truncation().returnedCandidateCount());
            object.property("omittedCandidateCount", snapshot.truncation().omittedCandidateCount());
            object.object("omittedByPath", omitted -> snapshot.truncation().omittedByPath().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> omitted.property(entry.getKey(), entry.getValue())));
        });
        writer.array("diagnostics", array -> snapshot.diagnostics().forEach(diagnostic ->
            array.objectValue(item -> writeDiagnostic(item, diagnostic))));
    }

    private static void writeContext(JsonWriter writer, Context context) {
        writer.property("knowledgeProfile", context.knowledgeProfile().name());
        writer.stringArray("enabledPacks", context.enabledPacks().stream().sorted().toList());
        writer.stringArray("disabledPacks", context.disabledPacks().stream().sorted().toList());
        writer.property("includePlugins", context.includePlugins());
        writer.property("includeLearnedMacros", context.includeLearnedMacros());
        writer.property("minMacroProofStatus", context.minMacroProofStatus().name());
        writer.property("searchProfile", context.searchProfile());
        writer.property("goalExpression", context.goalExpression());
        writer.property("maxCandidatesPerPosition", context.maxCandidatesPerPosition());
        writer.property("maxCandidatesTotal", context.maxCandidatesTotal());
        writer.stringArray("assumptions", context.assumptions());
        writer.property("includeRejectedCandidates", context.includeRejectedCandidates());
        writer.property("selectedCandidateId", context.selectedCandidateId());
    }

    private static void writeNode(JsonWriter writer, AstNode node) {
        writer.property("pathKey", node.pathKey());
        writer.property("parentPathKey", node.parentPathKey());
        writer.stringArray("childPathKeys", node.childPathKeys());
        writer.property("nodeKind", node.nodeKind());
        writer.property("label", node.label());
        writer.property("subtree", node.subtree());
        writer.property("depth", node.depth());
        writer.property("preorderIndex", node.preorderIndex());
        writer.stringArray("candidateIds", node.candidateIds());
        writer.property("candidateCount", node.candidateCount());
        writer.property("omittedCandidateCount", node.omittedCandidateCount());
    }

    private static void writeCandidate(JsonWriter writer, ApplicableMove candidate) {
        writer.property("candidateId", candidate.candidateId());
        writer.property("pathKey", candidate.pathKey());
        writer.property("ruleId", candidate.ruleId());
        writer.property("displayName", candidate.displayName());
        writer.property("origin", candidate.origin().name());
        writer.property("sourceReference", candidate.sourceReference());
        writer.property("license", candidate.license());
        writer.property("ruleKind", candidate.ruleKind());
        writer.array("bindings", array -> candidate.bindings().forEach(binding ->
            array.objectValue(item -> writeBinding(item, binding))));
        writer.stringArray("assumptions", candidate.assumptions());
        writer.property("subtreeBefore", candidate.subtreeBefore());
        writer.property("subtreeAfter", candidate.subtreeAfter());
        writer.property("expressionBefore", candidate.expressionBefore());
        writer.property("expressionAfter", candidate.expressionAfter());
        writer.property("applicable", candidate.applicable());
        writer.property("validationStatus", candidate.validationStatus());
        writer.property("equivalencePreserving", candidate.equivalencePreserving());
        writer.property("mayIncreaseComplexity", candidate.mayIncreaseComplexity());
        writer.property("estimatedCostDelta", candidate.estimatedCostDelta());
        writer.property("outcome", candidate.outcome().name());
        writer.property("selected", candidate.selected());
        writer.property("orderingKey", candidate.orderingKey());
        if (candidate.macroEvidence() == null) {
            writer.nullProperty("macroEvidence");
        } else {
            writer.object("macroEvidence", object -> writeMacroEvidence(object, candidate.macroEvidence()));
        }
    }

    private static void writeBinding(JsonWriter writer, Binding binding) {
        writer.property("name", binding.name());
        writer.property("value", binding.value());
        writer.property("kind", binding.kind());
    }

    private static void writeMacroEvidence(JsonWriter writer, MacroEvidence evidence) {
        writer.property("reusableRuleId", evidence.reusableRuleId());
        writer.stringArray("supportingPathIds", evidence.supportingPathIds());
        writer.property("confidenceScore", evidence.confidenceScore());
        writer.property("occurrenceCount", evidence.occurrenceCount());
        writer.property("compressionRatio", evidence.compressionRatio());
        writer.array("atomicSteps", array -> evidence.atomicSteps().forEach(step ->
            array.objectValue(item -> writeAtomicStep(item, step))));
    }

    private static void writeAtomicStep(JsonWriter writer, AtomicStep step) {
        writer.property("index", step.index());
        writer.property("beforeExpression", step.beforeExpression());
        writer.property("afterExpression", step.afterExpression());
        writer.property("ruleId", step.ruleId());
        writer.property("ruleKind", step.ruleKind());
        writer.stringArray("assumptions", step.assumptions());
    }

    private static void writeState(JsonWriter writer, SearchState state) {
        writer.property("stateId", state.stateId());
        writer.property("expression", state.expression());
        writer.property("canonicalExpression", state.canonicalExpression());
        writer.property("depth", state.depth());
        writer.property("target", state.target());
    }

    private static void writeEdge(JsonWriter writer, SearchEdge edge) {
        writer.property("edgeId", edge.edgeId());
        writer.property("fromStateId", edge.fromStateId());
        writer.property("toStateId", edge.toStateId());
        writer.property("fromExpression", edge.fromExpression());
        writer.property("toExpression", edge.toExpression());
        writer.property("candidateId", edge.candidateId());
        writer.property("pathKey", edge.pathKey());
        writer.property("ruleId", edge.ruleId());
        writer.property("origin", edge.origin().name());
        writer.property("outcome", edge.outcome().name());
    }

    private static void writeEvent(JsonWriter writer, SearchEvent event) {
        writer.property("sequence", event.sequence());
        writer.property("stateId", event.stateId());
        writer.property("expression", event.expression());
        writer.property("candidateId", event.candidateId());
        writer.property("pathKey", event.pathKey());
        writer.property("ruleId", event.ruleId());
        writer.property("outcome", event.outcome().name());
        writer.property("detail", event.detail());
    }

    private static void writeDiagnostic(JsonWriter writer, Diagnostic diagnostic) {
        writer.property("code", diagnostic.code());
        writer.property("message", diagnostic.message());
        writer.property("pathKey", diagnostic.pathKey());
    }
}
