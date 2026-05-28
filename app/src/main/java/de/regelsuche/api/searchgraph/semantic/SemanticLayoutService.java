package de.regelsuche.api.searchgraph.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SemanticLayoutService {

    private static final double CANVAS_CENTER_X = 0.0;
    private static final double TOP_PADDING = 80.0;
    private static final double VERTICAL_GAP = 220.0;
    private static final double SIDE_COLUMN_GAP = 320.0;

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
        SemanticLayoutKind resolvedKind = kind == null ? SemanticLayoutKind.MAIN_PATH_LAYERED : kind;
        Map<String, SemanticPositionDto> positions = resolvedKind == SemanticLayoutKind.COMPLEXITY_AXIS
            ? horizontalLayout(layers)
            : verticalMainPathLayout(ordered, layers);
        List<SemanticLayerDto> layerDtos = new ArrayList<>();
        for (Map.Entry<Integer, List<SemanticGraphNodeDto>> entry : layers.entrySet()) {
            int layer = entry.getKey();
            List<SemanticGraphNodeDto> layerNodes = entry.getValue();
            layerNodes.sort(Comparator.comparing(SemanticGraphNodeDto::id));
            layerDtos.add(new SemanticLayerDto(layer, "depth " + layer,
                layerNodes.stream().map(SemanticGraphNodeDto::id).toList()));
        }
        return new SemanticLayoutDto(positions, layerDtos, resolvedKind);
    }

    private Map<String, SemanticPositionDto> verticalMainPathLayout(
        List<SemanticGraphNodeDto> ordered,
        Map<Integer, List<SemanticGraphNodeDto>> layers
    ) {
        Map<String, SemanticPositionDto> positions = new LinkedHashMap<>();
        List<SemanticGraphNodeDto> mainPath = ordered.stream()
            .filter(SemanticGraphNodeDto::onMainPath)
            .sorted(Comparator.comparingInt(SemanticGraphNodeDto::minDepth).thenComparing(SemanticGraphNodeDto::id))
            .toList();
        Map<Integer, Integer> mainIndexByDepth = new LinkedHashMap<>();
        for (int i = 0; i < mainPath.size(); i++) {
            SemanticGraphNodeDto node = mainPath.get(i);
            positions.put(node.id(), new SemanticPositionDto(
                CANVAS_CENTER_X,
                TOP_PADDING + i * VERTICAL_GAP,
                Math.max(0, node.minDepth()),
                node.bestScore(),
                node.minDepth()
            ));
            mainIndexByDepth.putIfAbsent(Math.max(0, node.minDepth()), i);
        }

        int fallbackIndex = mainPath.size();
        for (Map.Entry<Integer, List<SemanticGraphNodeDto>> entry : layers.entrySet()) {
            int layer = entry.getKey();
            List<SemanticGraphNodeDto> sideNodes = entry.getValue().stream()
                .filter(node -> !positions.containsKey(node.id()))
                .sorted(Comparator.comparing(SemanticGraphNodeDto::id))
                .toList();
            int anchorIndex = mainIndexByDepth.getOrDefault(layer, fallbackIndex++);
            for (int i = 0; i < sideNodes.size(); i++) {
                SemanticGraphNodeDto node = sideNodes.get(i);
                double sign = (i % 2 == 0) ? -1.0 : 1.0;
                double column = 1 + (i / 2);
                positions.put(node.id(), new SemanticPositionDto(
                    CANVAS_CENTER_X + sign * column * SIDE_COLUMN_GAP,
                    TOP_PADDING + anchorIndex * VERTICAL_GAP,
                    layer,
                    node.bestScore(),
                    node.minDepth()
                ));
            }
        }
        return positions;
    }

    private Map<String, SemanticPositionDto> horizontalLayout(Map<Integer, List<SemanticGraphNodeDto>> layers) {
        Map<String, SemanticPositionDto> positions = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<SemanticGraphNodeDto>> entry : layers.entrySet()) {
            int layer = entry.getKey();
            List<SemanticGraphNodeDto> layerNodes = entry.getValue();
            layerNodes.sort(Comparator.comparing(SemanticGraphNodeDto::id));
            for (int i = 0; i < layerNodes.size(); i++) {
                SemanticGraphNodeDto node = layerNodes.get(i);
                double sign = (i % 2 == 0) ? 1.0 : -1.0;
                double y = node.onMainPath() ? 0.0 : sign * (80 + (i / 2) * 80.0);
                double x = layer * 260.0;
                positions.put(node.id(), new SemanticPositionDto(x, y, layer, node.bestScore(), node.minDepth()));
            }
        }
        return positions;
    }
}
