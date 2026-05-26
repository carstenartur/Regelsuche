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
}
