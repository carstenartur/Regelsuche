package de.regelsuche.api.searchgraph.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.searchgraph.SearchGraphAssembler;
import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.discovery.TransformationStep;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.GraphSnapshot;
import de.regelsuche.scoring.ExpressionScore;
import de.regelsuche.search.SimplificationSuccess;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SemanticSearchGraphAssemblerTest {

    @Test
    void collapsesLowSignalNormalizationEdges() {
        GraphSnapshot snapshot = new GraphSnapshot(
            List.of("a+b", "b+a", "a"),
            List.of(
                new GraphEdge("a+b", "b+a", "commutativity", 0, 0, "p#0", "", 5, 5,
                    RewriteKind.NORMALIZE, false, 0, true, CandidateProofStatus.OBSERVED),
                new GraphEdge("b+a", "a", "remove_zero", 1, 2, "p#1", "", 5, 3,
                    RewriteKind.SIMPLIFY, false, -2, true, CandidateProofStatus.OBSERVED)
            )
        );
        var raw = new SearchGraphAssembler().assemble(snapshot,
            List.of(new SimplificationSuccess("a+b", "a", "remove_zero", 1, 2, Instant.now())));
        var semantic = new SemanticSearchGraphAssembler().assemble(
            raw,
            List.of(),
            List.of(),
            SemanticGraphViewMode.SEMANTIC,
            false,
            true,
            false,
            12,
            8
        );
        assertTrue(semantic.edges().stream().anyMatch(e -> e.kind() == SemanticEdgeKind.LOW_SIGNAL_COLLAPSED));
    }

    @Test
    void hidesAlternativeOrphansWhenAlternativesAndLowSignalAreCollapsed() {
        var raw = new SearchGraphDto(
            List.of(
                node("a + 0 + 0", 0, 10),
                node("a + 0", 1, 8),
                node("a", 2, 5),
                node("b + 0", 0, 6),
                node("b", 1, 3),
                node("lonely", 4, 1)
            ),
            List.of(
                edge("a + 0 + 0", "a + 0", "ast_canonical_normalize", RewriteKind.NORMALIZE, 0),
                edge("a + 0", "a", "remove_zero", RewriteKind.SIMPLIFY, -3),
                edge("b + 0", "b", "remove_zero", RewriteKind.SIMPLIFY, -3)
            ),
            List.of(),
            null
        );
        var mainPath = path(
            "main",
            "a + 0 + 0",
            "a",
            List.of(
                new TransformationStep(0, "a + 0 + 0", "a + 0",
                    "ast_canonical_normalize", RewriteKind.NORMALIZE, 10, 10, true, ""),
                new TransformationStep(1, "a + 0", "a",
                    "remove_zero", RewriteKind.SIMPLIFY, 10, 7, true, "")
            )
        );

        var semantic = new SemanticSearchGraphAssembler().assemble(
            raw,
            List.of(mainPath),
            List.of(),
            SemanticGraphViewMode.SEMANTIC,
            false,
            false,
            false,
            12,
            8
        );

        Set<String> connected = new HashSet<>();
        semantic.edges().forEach(e -> {
            connected.add(e.from());
            connected.add(e.to());
        });
        long nonEndpointOrphans = semantic.nodes().stream()
            .filter(n -> !n.explicitEndpoint())
            .filter(n -> !connected.contains(n.id()))
            .count();
        assertEquals(0, nonEndpointOrphans);
        assertTrue(semantic.nodes().stream().noneMatch(n -> n.representativeExpression().equals("b + 0")));
        assertTrue(semantic.nodes().stream().noneMatch(n -> n.representativeExpression().equals("b")));
        assertTrue(semantic.nodes().stream().noneMatch(n -> n.representativeExpression().equals("lonely")));
        assertTrue(semantic.nodes().stream().noneMatch(n -> n.representativeExpression().equals("a + 0")));
        assertTrue(semantic.edges().stream().anyMatch(e -> e.kind() == SemanticEdgeKind.MAIN_STEP
            && e.hiddenStepCount() == 1
            && e.sourceEdgeIds().equals(List.of(
                "a + 0 + 0->a + 0:ast_canonical_normalize",
                "a + 0->a:remove_zero"
            ))));
        assertEquals(1, semantic.stats().hiddenAlternativeCount());
    }

    @Test
    void compressesHiddenLowSignalMainPathWithoutDestroyingExplanationPath() {
        var raw = new SearchGraphDto(
            List.of(
                node("start", 0, 10),
                node("normalized", 1, 10),
                node("middle", 2, 7),
                node("goal", 3, 3),
                node("branch", 2, 6)
            ),
            List.of(
                edge("start", "normalized", "ast_canonical_normalize", RewriteKind.NORMALIZE, 0),
                edge("normalized", "middle", "factor_terms", RewriteKind.SIMPLIFY, -3),
                edge("middle", "goal", "collect_terms", RewriteKind.SIMPLIFY, -4),
                edge("start", "branch", "alternative_branch", RewriteKind.SIMPLIFY, -4)
            ),
            List.of(),
            null
        );
        var mainPath = path(
            "main",
            "start",
            "goal",
            List.of(
                new TransformationStep(0, "start", "normalized",
                    "ast_canonical_normalize", RewriteKind.NORMALIZE, 10, 10, true, ""),
                new TransformationStep(1, "normalized", "middle",
                    "factor_terms", RewriteKind.SIMPLIFY, 10, 7, true, ""),
                new TransformationStep(2, "middle", "goal",
                    "collect_terms", RewriteKind.SIMPLIFY, 7, 3, true, "")
            )
        );

        var semantic = new SemanticSearchGraphAssembler().assemble(
            raw,
            List.of(mainPath),
            List.of(),
            SemanticGraphViewMode.SEMANTIC,
            false,
            false,
            false,
            12,
            8
        );

        assertEquals(3, semantic.nodes().size());
        assertTrue(semantic.nodes().stream().anyMatch(n -> n.representativeExpression().equals("start")));
        assertTrue(semantic.nodes().stream().anyMatch(n -> n.representativeExpression().equals("middle")));
        assertTrue(semantic.nodes().stream().anyMatch(n -> n.representativeExpression().equals("goal")));
        assertTrue(semantic.nodes().stream().noneMatch(n -> n.representativeExpression().equals("normalized")));
        assertTrue(semantic.nodes().stream().noneMatch(n -> n.representativeExpression().equals("branch")));
        assertTrue(semantic.edges().size() >= semantic.nodes().size() - 1);
        assertEquals(3, semantic.nodes().stream().filter(SemanticGraphNodeDto::onMainPath).count());

        Map<String, String> nodeIds = semantic.nodes().stream()
            .collect(Collectors.toMap(SemanticGraphNodeDto::representativeExpression, SemanticGraphNodeDto::id));
        var shortcut = semantic.edges().stream()
            .filter(e -> e.from().equals(nodeIds.get("start")) && e.to().equals(nodeIds.get("middle")))
            .findFirst()
            .orElseThrow();
        assertEquals(SemanticEdgeKind.MAIN_STEP, shortcut.kind());
        assertEquals(1, shortcut.hiddenStepCount());
        assertEquals(List.of(
            "start->normalized:ast_canonical_normalize",
            "normalized->middle:factor_terms"
        ), shortcut.sourceEdgeIds());
    }

    private static SearchGraphNodeDto node(String expression, int depth, int score) {
        return new SearchGraphNodeDto(
            expression,
            expression,
            "",
            score,
            depth,
            1,
            false,
            false,
            CandidateProofStatus.OBSERVED,
            ""
        );
    }

    private static SearchGraphEdgeDto edge(
        String from,
        String to,
        String ruleId,
        RewriteKind kind,
        int scoreDelta
    ) {
        return new SearchGraphEdgeDto(
            from,
            to,
            ruleId,
            kind,
            scoreDelta,
            List.of(),
            List.of("path"),
            true
        );
    }

    private static DiscoveredTransformation path(
        String id,
        String original,
        String improved,
        List<TransformationStep> steps
    ) {
        return new DiscoveredTransformation(
            id,
            original,
            improved,
            steps,
            new ExpressionScore(10, 0, 0, 0, 0),
            new ExpressionScore(5, 0, 0, 0, 0),
            5,
            CandidateProofStatus.OBSERVED,
            Instant.now(),
            ""
        );
    }
}
