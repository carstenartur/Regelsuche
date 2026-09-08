package de.regelsuche.graph;

import de.regelsuche.transform.RecordedExecution;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.LinkedHashMap;
import java.util.Map;

/** Property mapping shared by Neo4j writes and reads; missing historic evidence stays unknown. */
final class GraphEdgePersistence {
    private GraphEdgePersistence() { }

    static Map<String, Object> properties(GraphEdge edge) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("from", edge.fromExpression()); values.put("to", edge.toExpression());
        values.put("rule", edge.transformationRule()); values.put("depth", edge.depth());
        values.put("improvement", edge.improvement()); values.put("pathId", edge.pathId());
        values.put("canonicalHash", edge.canonicalHash()); values.put("scoreBefore", edge.scoreBefore());
        values.put("scoreAfter", edge.scoreAfter()); values.put("rewriteKind", edge.rewriteKind().name());
        values.put("mayIncreaseComplexity", edge.mayIncreaseComplexity());
        values.put("estimatedCostDelta", edge.estimatedCostDelta());
        values.put("equivalencePreservingByConstruction", edge.equivalencePreservingByConstruction());
        values.put("validationStatus", edge.validationStatus().name());
        values.put("executionIdentity", edge.executionIdentity());
        values.put("execution", edge.execution() == null ? null : edge.execution().toCanonicalJson());
        return values;
    }

    static GraphEdge read(String from, String to, Map<String, Object> values) {
        var execution = RecordedExecution.readOptional(values);
        if (execution != null && !execution.contentHash().equals(values.get("executionIdentity"))) {
            throw new IllegalArgumentException("stored graph execution identity differs from its evidence");
        }
        return new GraphEdge(from, to, text(values, "rule", ""), integer(values, "depth"), integer(values, "improvement"),
            text(values, "pathId", ""), text(values, "canonicalHash", ""), integer(values, "scoreBefore"), integer(values, "scoreAfter"),
            RewriteKind.valueOf(text(values, "rewriteKind", RewriteKind.NORMALIZE.name())),
            bool(values, "mayIncreaseComplexity", false), integer(values, "estimatedCostDelta"),
            bool(values, "equivalencePreservingByConstruction", true),
            CandidateProofStatus.valueOf(text(values, "validationStatus", CandidateProofStatus.OBSERVED.name())), null, execution);
    }

    private static String text(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String result)) throw new IllegalArgumentException("invalid graph property: " + key);
        return result;
    }
    private static int integer(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return 0;
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new IllegalArgumentException("invalid graph integer: " + key);
        return Math.toIntExact(((Number) value).longValue());
    }
    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException("invalid graph boolean: " + key);
        return result;
    }
}
