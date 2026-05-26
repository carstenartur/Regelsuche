package de.regelsuche.api.searchgraph.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticLayoutServiceTest {

    private final SemanticLayoutService service = new SemanticLayoutService();

    @Test
    void mainPathIsMonotoneByDepth() {
        List<SemanticGraphNodeDto> nodes = List.of(
            node("n0", 0, true),
            node("n1", 1, true),
            node("n2", 2, true)
        );
        SemanticLayoutDto layout = service.layout(nodes, List.of(), SemanticLayoutKind.MAIN_PATH_LAYERED);
        assertTrue(layout.positions().get("n0").x() < layout.positions().get("n1").x());
        assertTrue(layout.positions().get("n1").x() < layout.positions().get("n2").x());
        assertEquals(0.0, layout.positions().get("n1").y());
    }

    @Test
    void positionsAreStableForSameInput() {
        List<SemanticGraphNodeDto> nodes = List.of(node("a", 0, false), node("b", 1, false));
        SemanticLayoutDto one = service.layout(nodes, List.of(), SemanticLayoutKind.MAIN_PATH_LAYERED);
        SemanticLayoutDto two = service.layout(nodes, List.of(), SemanticLayoutKind.MAIN_PATH_LAYERED);
        assertEquals(one.positions(), two.positions());
    }

    @Test
    void uglyGraphFixtureKeepsSemanticMainPathStableAndReadable() {
        List<SemanticGraphNodeDto> nodes = List.of(
            node("seed", 0, true),
            node("normalize-a", 1, false),
            node("normalize-b", 1, false),
            node("expand-noise", 1, false),
            node("main-expand", 1, true),
            node("dead-end-a", 2, false),
            node("dead-end-b", 2, false),
            node("variant-a", 2, false),
            node("variant-b", 2, false),
            node("main-factor", 2, true),
            node("detour-a", 3, false),
            node("detour-b", 3, false),
            node("detour-c", 3, false),
            node("main-cancel", 3, true),
            node("macro-a", 4, false),
            node("macro-b", 4, false),
            node("macro-c", 4, false),
            node("main-macro", 4, true),
            node("proof-a", 5, false),
            node("proof-b", 5, false),
            node("target", 5, true)
        );
        List<SemanticGraphEdgeDto> edges = List.of(
            edge("seed", "normalize-a", SemanticEdgeKind.LOW_SIGNAL_COLLAPSED),
            edge("seed", "normalize-b", SemanticEdgeKind.LOW_SIGNAL_COLLAPSED),
            edge("seed", "expand-noise", SemanticEdgeKind.ALTERNATIVE),
            edge("seed", "main-expand", SemanticEdgeKind.MAIN_STEP),
            edge("normalize-a", "variant-a", SemanticEdgeKind.ALTERNATIVE),
            edge("normalize-b", "variant-b", SemanticEdgeKind.ALTERNATIVE),
            edge("expand-noise", "dead-end-a", SemanticEdgeKind.ALTERNATIVE),
            edge("expand-noise", "dead-end-b", SemanticEdgeKind.ALTERNATIVE),
            edge("main-expand", "main-factor", SemanticEdgeKind.MAIN_STEP),
            edge("variant-a", "detour-a", SemanticEdgeKind.ALTERNATIVE),
            edge("variant-b", "detour-b", SemanticEdgeKind.ALTERNATIVE),
            edge("dead-end-a", "detour-c", SemanticEdgeKind.ALTERNATIVE),
            edge("main-factor", "main-cancel", SemanticEdgeKind.MAIN_STEP),
            edge("detour-a", "macro-a", SemanticEdgeKind.MACRO_STEP),
            edge("detour-b", "macro-b", SemanticEdgeKind.MACRO_STEP),
            edge("detour-c", "macro-c", SemanticEdgeKind.MACRO_STEP),
            edge("main-cancel", "main-macro", SemanticEdgeKind.MACRO_STEP),
            edge("macro-a", "proof-a", SemanticEdgeKind.ALTERNATIVE),
            edge("macro-b", "proof-b", SemanticEdgeKind.ALTERNATIVE),
            edge("main-macro", "target", SemanticEdgeKind.MAIN_STEP)
        );

        SemanticLayoutDto first = service.layout(nodes, edges, SemanticLayoutKind.CLUSTERED_EXPLANATION);
        SemanticLayoutDto second = service.layout(nodes, edges, SemanticLayoutKind.CLUSTERED_EXPLANATION);

        assertEquals(first.positions(), second.positions());
        assertEquals(nodes.size(), first.positions().size());
        assertTrue(nodes.stream()
            .filter(SemanticGraphNodeDto::onMainPath)
            .allMatch(node -> first.positions().get(node.id()).y() == 0.0));
        assertTrue(first.positions().get("seed").x() < first.positions().get("main-expand").x());
        assertTrue(first.positions().get("main-expand").x() < first.positions().get("main-factor").x());
        assertTrue(first.positions().get("main-factor").x() < first.positions().get("main-cancel").x());
        assertTrue(first.positions().get("main-cancel").x() < first.positions().get("main-macro").x());
        assertTrue(first.positions().get("main-macro").x() < first.positions().get("target").x());
        assertTrue(first.positions().values().stream()
            .noneMatch(position -> Double.isNaN(position.x()) || Double.isNaN(position.y())));
    }

    private static SemanticGraphNodeDto node(String id, int depth, boolean onMainPath) {
        return new SemanticGraphNodeDto(
            id,
            id,
            id,
            id,
            null,
            List.of(id),
            1,
            depth,
            1,
            onMainPath,
            false,
            "",
            SemanticNodeKind.INTERMEDIATE
        );
    }

    private static SemanticGraphEdgeDto edge(String from, String to, SemanticEdgeKind kind) {
        return new SemanticGraphEdgeDto(from, to, kind.name().toLowerCase(), kind.name().toLowerCase(),
            null, kind, 1, kind == SemanticEdgeKind.LOW_SIGNAL_COLLAPSED ? 1 : 0,
            kind == SemanticEdgeKind.LOW_SIGNAL_COLLAPSED, kind == SemanticEdgeKind.MACRO_STEP,
            null, List.of(from + "->" + to), kind == SemanticEdgeKind.MAIN_STEP ? 1.0 : 0.2);
    }
}
