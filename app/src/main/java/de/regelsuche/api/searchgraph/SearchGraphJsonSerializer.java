package de.regelsuche.api.searchgraph;

import de.regelsuche.json.JsonWriter;
import de.regelsuche.export.layout.MathLayoutJsonWriter;
import java.util.Map;

/**
 * Renders {@link SearchGraphDto} instances to JSON using the project's
 * built-in {@link JsonWriter}. Kept separate from the DTOs so the records
 * remain framework-free.
 */
public final class SearchGraphJsonSerializer {

    private SearchGraphJsonSerializer() {
    }

    public static String toJson(SearchGraphDto dto) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.array("nodes", w -> dto.nodes().forEach(node ->
            w.objectValue(inner -> {
                inner.property("id", node.id());
                inner.property("expression", node.expression());
                inner.property("latex", node.latex());
                inner.property("expressionLatex", node.expressionLatex());
                MathLayoutJsonWriter.write(inner, "layout", node.layout());
                inner.property("score", node.score());
                inner.property("depth", node.depth());
                inner.property("visitedCount", node.visitedCount());
                inner.property("isBest", node.isBest());
                inner.property("isDeadEnd", node.isDeadEnd());
                inner.property("candidateStatus", node.candidateStatus().name());
                inner.property("clusterId", node.clusterId());
            })));
        writer.array("edges", w -> dto.edges().forEach(edge ->
            w.objectValue(inner -> {
                inner.property("from", edge.from());
                inner.property("to", edge.to());
                inner.property("ruleId", edge.ruleId());
                inner.property("ruleLatex", edge.ruleLatex());
                MathLayoutJsonWriter.write(inner, "layout", edge.layout());
                inner.property("ruleKind", edge.ruleKind().name());
                inner.property("scoreDelta", edge.scoreDelta());
                inner.stringArray("assumptions", edge.assumptions());
                inner.stringArray("pathIds", edge.pathIds());
                inner.property("equivalencePreserving", edge.equivalencePreserving());
            })));
        writer.array("clusters", w -> dto.clusters().forEach(cluster ->
            w.objectValue(inner -> {
                inner.property("id", cluster.id());
                inner.property("label", cluster.label());
                inner.property("type", cluster.type().name());
                inner.stringArray("nodeIds", cluster.nodeIds());
                inner.stringArray("supportingPathIds", cluster.supportingPathIds());
                inner.property("cohesionScore", cluster.cohesionScore());
            })));
        writer.object("stats", stats -> {
            SearchGraphStatsDto statsDto = dto.stats();
            stats.property("nodesVisited", statsDto.nodesVisited());
            stats.property("edgesGenerated", statsDto.edgesGenerated());
            stats.property("deadEnds", statsDto.deadEnds());
            stats.property("bestScore", statsDto.bestScore());
            stats.property("averageBranchingFactor", statsDto.averageBranchingFactor());
            stats.property("maxDepthReached", statsDto.maxDepthReached());
            stats.object("ruleUsageFrequency", rules -> {
                for (Map.Entry<String, Integer> entry : statsDto.ruleUsageFrequency().entrySet()) {
                    rules.property(entry.getKey(), entry.getValue());
                }
            });
            stats.stringArray("mostUsefulRules", statsDto.mostUsefulRules());
            stats.property("candidateCount", statsDto.candidateCount());
            stats.property("macroRuleCount", statsDto.macroRuleCount());
        });
        writer.endObject();
        return writer.toString();
    }
}
