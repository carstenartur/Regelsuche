package de.regelsuche.api.searchgraph.semantic;

import de.regelsuche.export.layout.MathLayoutJsonWriter;
import de.regelsuche.json.JsonWriter;

public final class SemanticSearchGraphJsonSerializer {

    private SemanticSearchGraphJsonSerializer() {
    }

    public static String toJson(SemanticSearchGraphDto dto) {
        JsonWriter writer = new JsonWriter();
        writer.beginObject();
        writer.array("nodes", w -> dto.nodes().forEach(node -> w.objectValue(inner -> {
            inner.property("id", node.id());
            inner.property("canonicalExpression", node.canonicalExpression());
            inner.property("representativeExpression", node.representativeExpression());
            inner.property("representativeLatex", node.representativeLatex());
            MathLayoutJsonWriter.write(inner, "layout", node.layout());
            inner.stringArray("variants", node.variants());
            inner.property("variantCount", node.variantCount());
            inner.property("minDepth", node.minDepth());
            inner.property("bestScore", node.bestScore());
            inner.property("onMainPath", node.onMainPath());
            inner.property("collapsed", node.collapsed());
            inner.property("clusterId", node.clusterId());
            inner.property("kind", node.kind().name());
            inner.property("explicitEndpoint", node.explicitEndpoint());
        })));
        writer.array("edges", w -> dto.edges().forEach(edge -> w.objectValue(inner -> {
            inner.property("from", edge.from());
            inner.property("to", edge.to());
            inner.property("ruleId", edge.ruleId());
            inner.property("ruleLatex", edge.ruleLatex());
            MathLayoutJsonWriter.write(inner, "layout", edge.layout());
            inner.property("kind", edge.kind().name());
            inner.property("atomicStepCount", edge.atomicStepCount());
            inner.property("hiddenStepCount", edge.hiddenStepCount());
            inner.property("lowSignal", edge.lowSignal());
            inner.property("macroMove", edge.macroMove());
            inner.stringArray("sourceEdgeIds", edge.sourceEdgeIds());
            inner.property("interestingness", edge.interestingness());
            if (edge.macroMoveExpansion() == null) {
                inner.nullProperty("macroMoveExpansion");
            } else {
                inner.object("macroMoveExpansion", macro -> {
                    macro.property("macroRuleId", edge.macroMoveExpansion().macroRuleId());
                    macro.property("fromExpression", edge.macroMoveExpansion().fromExpression());
                    macro.property("toExpression", edge.macroMoveExpansion().toExpression());
                    macro.property("compressionRatio", edge.macroMoveExpansion().compressionRatio());
                    macro.stringArray("supportingPathIds", edge.macroMoveExpansion().supportingPathIds());
                    macro.array("atomicSteps", steps -> edge.macroMoveExpansion().atomicSteps().forEach(step ->
                        steps.objectValue(s -> {
                            s.property("index", step.index());
                            s.property("beforeExpression", step.beforeExpression());
                            s.property("afterExpression", step.afterExpression());
                            s.property("ruleId", step.ruleId());
                        })));
                });
            }
        })));
        writer.array("clusters", w -> dto.clusters().forEach(cluster -> w.objectValue(inner -> {
            inner.property("id", cluster.id());
            inner.property("label", cluster.label());
            inner.property("kind", cluster.kind().name());
            inner.stringArray("nodeIds", cluster.nodeIds());
            inner.property("hiddenNodeCount", cluster.hiddenNodeCount());
            inner.property("cohesion", cluster.cohesion());
        })));
        writer.object("stats", stats -> {
            stats.property("rawNodeCount", dto.stats().rawNodeCount());
            stats.property("rawEdgeCount", dto.stats().rawEdgeCount());
            stats.property("visibleNodeCount", dto.stats().visibleNodeCount());
            stats.property("visibleEdgeCount", dto.stats().visibleEdgeCount());
            stats.property("collapsedVariantCount", dto.stats().collapsedVariantCount());
            stats.property("lowSignalEdgeCount", dto.stats().lowSignalEdgeCount());
            stats.property("macroMoveEdgeCount", dto.stats().macroMoveEdgeCount());
            stats.property("mainPathLength", dto.stats().mainPathLength());
            stats.property("hiddenAlternativeCount", dto.stats().hiddenAlternativeCount());
        });
        writer.object("view", view -> {
            view.property("mode", dto.view().mode().name());
            view.property("showLowSignal", dto.view().showLowSignal());
            view.property("showAlternatives", dto.view().showAlternatives());
            view.property("showVariants", dto.view().showVariants());
            view.property("maxAlternatives", dto.view().maxAlternatives());
            view.property("maxVariantsPerCluster", dto.view().maxVariantsPerCluster());
            view.object("layout", layout -> {
                layout.property("kind", dto.view().layout().kind().name());
                layout.object("positions", pos -> dto.view().layout().positions().forEach((id, p) ->
                    pos.object(id, node -> {
                        node.property("x", p.x());
                        node.property("y", p.y());
                        node.property("layer", p.layer());
                        node.property("complexity", p.complexity());
                        node.property("depth", p.depth());
                    })));
                layout.array("layers", layers -> dto.view().layout().layers().forEach(layerDto ->
                    layers.objectValue(layer -> {
                        layer.property("index", layerDto.index());
                        layer.property("label", layerDto.label());
                        layer.stringArray("nodeIds", layerDto.nodeIds());
                    })));
            });
        });
        writer.endObject();
        return writer.toString();
    }

    public static String toMermaid(SemanticSearchGraphDto dto) {
        StringBuilder builder = new StringBuilder("graph TD\n");
        for (SemanticGraphNodeDto node : dto.nodes()) {
            builder.append("  ").append(node.id()).append("[\"")
                .append(node.representativeExpression().replace("\"", "'"))
                .append("\"]\n");
        }
        for (SemanticGraphEdgeDto edge : dto.edges()) {
            builder.append("  ").append(edge.from()).append(" -->|")
                .append(edge.ruleId().replace("\"", "'"))
                .append("| ").append(edge.to()).append("\n");
        }
        return builder.toString();
    }
}
