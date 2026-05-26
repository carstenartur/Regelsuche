package de.regelsuche.api.searchgraph.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SemanticLayoutService {

    public SemanticLayoutDto layout(
        List<SemanticGraphNodeDto> nodes,
        List<SemanticGraphEdgeDto> edges,
        SemanticLayoutKind kind
    ) {
        Map<Integer, List<SemanticGraphNodeDto>> layers = new LinkedHashMap<>();
        List<SemanticGraphNodeDto> ordered = new ArrayList<>(nodes == null ? List.of() : nodes);
        ordered.sort(Comparator.comparingInt(SemanticGraphNodeDto::minDepth).thenComparing(SemanticGraphNodeDto::id));
        for (SemanticGraphNodeDto node : ordered) {
            layers.computeIfAbsent(Math.max(0, node.minDepth()), k -> new ArrayList<>()).add(node);
        }
        Map<String, SemanticPositionDto> positions = new LinkedHashMap<>();
        List<SemanticLayerDto> layerDtos = new ArrayList<>();
        for (Map.Entry<Integer, List<SemanticGraphNodeDto>> entry : layers.entrySet()) {
            int layer = entry.getKey();
            List<SemanticGraphNodeDto> layerNodes = entry.getValue();
            layerNodes.sort(Comparator.comparing(SemanticGraphNodeDto::id));
            for (int i = 0; i < layerNodes.size(); i++) {
                SemanticGraphNodeDto node = layerNodes.get(i);
                double y;
                if (node.onMainPath()) {
                    y = 0;
                } else {
                    double sign = (i % 2 == 0) ? 1.0 : -1.0;
                    y = sign * (80 + (i / 2) * 80.0);
                }
                double x = layer * 260.0;
                positions.put(node.id(), new SemanticPositionDto(x, y, layer, node.bestScore(), node.minDepth()));
            }
            layerDtos.add(new SemanticLayerDto(layer, "depth " + layer,
                layerNodes.stream().map(SemanticGraphNodeDto::id).toList()));
        }
        return new SemanticLayoutDto(positions, layerDtos,
            kind == null ? SemanticLayoutKind.MAIN_PATH_LAYERED : kind);
    }
}
