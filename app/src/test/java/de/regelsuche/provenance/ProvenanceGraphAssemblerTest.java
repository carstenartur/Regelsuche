package de.regelsuche.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.regelsuche.api.IdentityReportDto;
import de.regelsuche.api.PathReplayDto;
import de.regelsuche.api.searchgraph.SearchGraphDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.api.searchgraph.SearchGraphRecord;
import de.regelsuche.api.searchgraph.SearchGraphStatsDto;
import de.regelsuche.mining.MacroRuleCandidate;
import de.regelsuche.mining.RuleStatus;
import de.regelsuche.transform.RewriteKind;
import de.regelsuche.validation.CandidateProofStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProvenanceGraphAssemblerTest {
    @Test
    void assemblesTypedDiscoveryProvenanceGraph() {
        ProvenanceGraph graph = new ProvenanceGraphAssembler().assemble(sampleRecord());

        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.SEARCH_RUN));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.SEED_EXPRESSION));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.MACRO_MOVE));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.HYPOTHESIS));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.PROOF_ATTEMPT));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.COUNTEREXAMPLE));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.ASSUMPTION_SIGNATURE));
        assertTrue(graph.nodes().stream().anyMatch(node -> node.type() == ProvenanceNodeType.BENCHMARK_RUN));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == ProvenanceEdgeType.SUPPORTED_BY));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == ProvenanceEdgeType.REFUTED_BY));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == ProvenanceEdgeType.GENERALIZES));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == ProvenanceEdgeType.DERIVED_FROM));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == ProvenanceEdgeType.USEFUL_FOR));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == ProvenanceEdgeType.REPLAY_OF));
        assertTrue(graph.edges().stream().anyMatch(edge -> edge.type() == ProvenanceEdgeType.GENERATED_BY));
    }

    @Test
    void supportsCoreProvenanceQueries() {
        ProvenanceGraph graph = new ProvenanceGraphAssembler().assemble(sampleRecord());
        ProvenanceGraphQueries queries = new ProvenanceGraphQueries();

        List<ProvenanceNode> strongest = queries.strongestHypotheses(graph, 1);
        assertEquals("hypothesis:run-1:hyp-strong", strongest.get(0).id());

        List<ProvenanceNode> refutedComplex = queries.hypothesesRefutedOnlyInDomain(graph, "complex");
        assertEquals(List.of("hypothesis:run-1:hyp-rejected"), refutedComplex.stream().map(ProvenanceNode::id).toList());

        List<ProvenanceNode> reusedMacros = queries.mostReusedMacroRules(graph, 1);
        assertEquals("macro:run-1:macro-step", reusedMacros.get(0).id());

        List<String> lineage = queries.derivationLineage(graph, "hypothesis:run-1:hyp-strong")
            .stream()
            .map(ProvenanceNode::id)
            .toList();
        assertTrue(lineage.contains("search-run:run-1"));
        assertTrue(lineage.contains("path:run-1:path-1"));
    }

    @Test
    void supportsAdvancedProvenanceAggregations() {
        ProvenanceGraph graph = new ProvenanceGraphAssembler().assemble(sampleRecord());
        ProvenanceGraphQueries queries = new ProvenanceGraphQueries();

        List<String> proofLineage = queries.proofLineage(graph, "run-1", "hyp-strong")
            .stream()
            .map(ProvenanceNode::id)
            .toList();
        assertTrue(proofLineage.contains("hypothesis:run-1:hyp-strong"));
        assertTrue(proofLineage.contains("proof:run-1:hyp-strong"));
        assertTrue(proofLineage.contains("path:run-1:path-1"));

        List<ProvenanceGraphQueries.HypothesisFamily> families =
            queries.quantitativeHypothesisFamilies(graph, "run-1", 0.5);
        assertTrue(families.stream().anyMatch(family ->
            family.proofStatusCounts().containsKey(CandidateProofStatus.FORMALLY_PROVED.name())));

        Map<String, ProvenanceGraphQueries.ErrorDistribution> errors =
            queries.errorDistributionByDomain(graph, "run-1");
        assertEquals(1, errors.get("complex").total());
    }

    @Test
    void detectsHypothesesSharedAcrossRuns() {
        ProvenanceGraph base = new ProvenanceGraphAssembler().assemble(sampleRecord());
        java.util.ArrayList<ProvenanceNode> nodes = new java.util.ArrayList<>(base.nodes());
        nodes.add(new ProvenanceNode("hypothesis:run-2:hyp-strong-copy", ProvenanceNodeType.HYPOTHESIS, "copy", Map.of(
            "leftPattern", "A",
            "rightPattern", "A + 0",
            "proofStatus", CandidateProofStatus.SYMBOLICALLY_VERIFIED.name()
        )));
        ProvenanceGraph merged = new ProvenanceGraph(nodes, base.edges());

        Map<String, List<ProvenanceNode>> shared =
            new ProvenanceGraphQueries().crossRunProvenance(merged, List.of("run-1", "run-2"));

        assertTrue(shared.values().stream().anyMatch(group -> group.size() == 2));
    }

    private static SearchGraphRecord sampleRecord() {
        SearchGraphNodeDto seed = new SearchGraphNodeDto(
            "n0", "x", "x", 4, 0, 1, false, false,
            CandidateProofStatus.OBSERVED, "cluster:demo"
        );
        SearchGraphNodeDto result = new SearchGraphNodeDto(
            "n1", "x + 0", "x + 0", 3, 1, 1, true, false,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED, "cluster:demo"
        );
        SearchGraphEdgeDto edge = new SearchGraphEdgeDto(
            "n0", "n1", "plus-zero", RewriteKind.SIMPLIFY, -1,
            List.of("x != 0"), List.of("path-1"), true
        );
        SearchGraphStatsDto stats = new SearchGraphStatsDto(
            2, 1, 0, 3, 1.0, 1, Map.of("plus-zero", 1), List.of("plus-zero"), 2, 1
        );
        SearchGraphDto graph = new SearchGraphDto(List.of(seed, result), List.of(edge), List.of(), stats);
        PathReplayDto replay = new PathReplayDto("path-1", List.of(
            new PathReplayDto.ReplayStep(0, "x", "x", "x + 0", "x + 0", "macro-step", "macro", -1, true)
        ));
        MacroRuleCandidate macro = new MacroRuleCandidate(
            "macro-step", List.of("r1", "r2", "r3"), 4, "A", "A + 0", 3.0,
            CandidateProofStatus.SYMBOLICALLY_VERIFIED, List.of("path-1"), List.of("x != 0")
        );
        IdentityReportDto strong = new IdentityReportDto(
            "hyp-strong", "A", "A + 0", List.of("r1", "r2"), 4, 2.0,
            CandidateProofStatus.FORMALLY_PROVED, RuleStatus.NEW, List.of("path-1")
        );
        IdentityReportDto rejected = new IdentityReportDto(
            "hyp-rejected", "sqrt(A^2)", "A", List.of("r3"), 1, 1.0,
            CandidateProofStatus.REJECTED, RuleStatus.NEW, List.of("path-1")
        );
        return new SearchGraphRecord(
            "run-1",
            Instant.parse("2026-05-25T00:00:00Z"),
            "DISCOVERY",
            List.of("complex"),
            graph,
            List.of(replay),
            List.of(macro),
            List.of(rejected, strong),
            Map.of("markdown", "# run")
        );
    }
}
