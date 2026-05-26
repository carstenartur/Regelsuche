package de.regelsuche.provenance;

import de.regelsuche.api.IdentityReportDto;
import de.regelsuche.api.PathReplayDto;
import de.regelsuche.api.searchgraph.SearchGraphEdgeDto;
import de.regelsuche.api.searchgraph.SearchGraphNodeDto;
import de.regelsuche.api.searchgraph.SearchGraphRecord;
import de.regelsuche.mining.MacroRuleCandidate;
import de.regelsuche.validation.CandidateProofStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a typed mathematical discovery provenance graph from a persisted search run. */
public final class ProvenanceGraphAssembler {
    public ProvenanceGraph assemble(SearchGraphRecord record) {
        Map<String, ProvenanceNode> nodes = new LinkedHashMap<>();
        List<ProvenanceEdge> edges = new ArrayList<>();
        String runId = "search-run:" + record.id();
        put(nodes, new ProvenanceNode(runId, ProvenanceNodeType.SEARCH_RUN, record.id(), Map.of(
            "createdAt", record.createdAt().toString(),
            "searchProfile", record.searchProfile(),
            "domains", String.join(",", record.domains())
        )));

        put(nodes, new ProvenanceNode("benchmark:" + record.id(), ProvenanceNodeType.BENCHMARK_RUN,
            "Benchmark " + record.id(), Map.of(
                "nodes", String.valueOf(record.graph().stats().nodesVisited()),
                "edges", String.valueOf(record.graph().stats().edgesGenerated()),
                "bestScore", String.valueOf(record.graph().stats().bestScore())
            )));
        edges.add(new ProvenanceEdge("benchmark:" + record.id(), runId, ProvenanceEdgeType.GENERATED_BY, Map.of()));

        for (SearchGraphNodeDto graphNode : record.graph().nodes()) {
            String nodeId = "seed:" + record.id() + ":" + graphNode.id();
            if (graphNode.depth() == 0) {
                put(nodes, new ProvenanceNode(nodeId, ProvenanceNodeType.SEED_EXPRESSION, graphNode.expression(),
                    Map.of("expression", graphNode.expression(), "score", String.valueOf(graphNode.score()))));
                edges.add(new ProvenanceEdge(nodeId, runId, ProvenanceEdgeType.GENERATED_BY, Map.of()));
            }
            if (graphNode.candidateStatus() == CandidateProofStatus.REJECTED) {
                String counterexampleId = "counterexample:" + record.id() + ":" + graphNode.id();
                put(nodes, new ProvenanceNode(counterexampleId, ProvenanceNodeType.COUNTEREXAMPLE,
                    "Counterexample for " + graphNode.expression(), Map.of(
                        "expression", graphNode.expression(),
                        "domains", String.join(",", record.domains())
                    )));
                edges.add(new ProvenanceEdge(counterexampleId, runId, ProvenanceEdgeType.GENERATED_BY, Map.of()));
            }
        }

        for (PathReplayDto replay : record.replays()) {
            String pathId = pathNodeId(record.id(), replay.pathId());
            put(nodes, new ProvenanceNode(pathId, ProvenanceNodeType.TRANSFORMATION_PATH, replay.pathId(),
                Map.of("stepCount", String.valueOf(replay.steps().size()))));
            edges.add(new ProvenanceEdge(pathId, runId, ProvenanceEdgeType.REPLAY_OF, Map.of()));
            for (PathReplayDto.ReplayStep step : replay.steps()) {
                if (step.macroMoveExpansion() != null) {
                    String macroId = "macro:" + record.id() + ":" + step.ruleId();
                    put(nodes, new ProvenanceNode(macroId, ProvenanceNodeType.MACRO_MOVE, step.ruleId(),
                        Map.of("ruleId", step.ruleId())));
                    edges.add(new ProvenanceEdge(macroId, pathId, ProvenanceEdgeType.USEFUL_FOR,
                        Map.of("stepIndex", String.valueOf(step.stepIndex()))));
                }
            }
        }

        for (SearchGraphEdgeDto edge : record.graph().edges()) {
            for (String assumption : edge.assumptions()) {
                String assumptionId = assumptionNodeId(record.id(), assumption);
                put(nodes, new ProvenanceNode(assumptionId, ProvenanceNodeType.ASSUMPTION_SIGNATURE, assumption,
                    Map.of("assumption", assumption)));
                edges.add(new ProvenanceEdge(assumptionId, runId, ProvenanceEdgeType.GENERATED_BY, Map.of()));
            }
        }

        for (MacroRuleCandidate macro : record.macroRules()) {
            String macroId = "macro:" + record.id() + ":" + macro.id();
            put(nodes, new ProvenanceNode(macroId, ProvenanceNodeType.MACRO_MOVE, macro.id(), Map.of(
                "leftPattern", macro.leftPattern(),
                "rightPattern", macro.rightPattern(),
                "occurrences", String.valueOf(macro.occurrences()),
                "compressionRatio", String.valueOf(macro.compressionRatio()),
                "proofStatus", macro.proofStatus().name()
            )));
            edges.add(new ProvenanceEdge(macroId, runId, ProvenanceEdgeType.DERIVED_FROM, Map.of()));
            for (String pathId : macro.supportingTransformationIds()) {
                put(nodes, new ProvenanceNode(pathNodeId(record.id(), pathId),
                    ProvenanceNodeType.TRANSFORMATION_PATH, pathId, Map.of()));
                edges.add(new ProvenanceEdge(macroId, pathNodeId(record.id(), pathId),
                    ProvenanceEdgeType.SUPPORTED_BY, Map.of()));
                edges.add(new ProvenanceEdge(macroId, pathNodeId(record.id(), pathId),
                    ProvenanceEdgeType.USEFUL_FOR, Map.of("source", "macro-support")));
            }
            for (String assumption : macro.assumptions()) {
                put(nodes, new ProvenanceNode(assumptionNodeId(record.id(), assumption),
                    ProvenanceNodeType.ASSUMPTION_SIGNATURE, assumption, Map.of("assumption", assumption)));
                edges.add(new ProvenanceEdge(macroId, assumptionNodeId(record.id(), assumption),
                    ProvenanceEdgeType.SUPPORTED_BY, Map.of("kind", "assumption")));
            }
        }

        for (IdentityReportDto identity : record.identities()) {
            String hypothesisId = "hypothesis:" + record.id() + ":" + identity.id();
            put(nodes, new ProvenanceNode(hypothesisId, ProvenanceNodeType.HYPOTHESIS, identity.id(), Map.of(
                "leftPattern", identity.leftPattern(),
                "rightPattern", identity.rightPattern(),
                "occurrences", String.valueOf(identity.occurrences()),
                "compressionRatio", String.valueOf(identity.compressionRatio()),
                "proofStatus", identity.proofStatus().name(),
                "knownRuleStatus", identity.knownRuleStatus().name()
            )));
            edges.add(new ProvenanceEdge(hypothesisId, runId, ProvenanceEdgeType.DERIVED_FROM, Map.of()));
            for (String pathId : identity.supportingTransformationIds()) {
                put(nodes, new ProvenanceNode(pathNodeId(record.id(), pathId),
                    ProvenanceNodeType.TRANSFORMATION_PATH, pathId, Map.of()));
                edges.add(new ProvenanceEdge(hypothesisId, pathNodeId(record.id(), pathId),
                    ProvenanceEdgeType.SUPPORTED_BY, Map.of()));
                edges.add(new ProvenanceEdge(hypothesisId, pathNodeId(record.id(), pathId),
                    ProvenanceEdgeType.GENERALIZES, Map.of()));
            }
            addProofOrCounterexample(record, nodes, edges, identity, hypothesisId);
        }

        return new ProvenanceGraph(List.copyOf(nodes.values()), edges);
    }

    private static void addProofOrCounterexample(
        SearchGraphRecord record,
        Map<String, ProvenanceNode> nodes,
        List<ProvenanceEdge> edges,
        IdentityReportDto identity,
        String hypothesisId
    ) {
        if (identity.proofStatus() == CandidateProofStatus.REJECTED) {
            String counterexampleId = "counterexample:" + record.id() + ":" + identity.id();
            put(nodes, new ProvenanceNode(counterexampleId, ProvenanceNodeType.COUNTEREXAMPLE,
                "Counterexample " + identity.id(), Map.of(
                    "failedAssumptions", "",
                    "domains", String.join(",", record.domains()),
                    "invalidRule", identity.id()
                )));
            edges.add(new ProvenanceEdge(hypothesisId, counterexampleId, ProvenanceEdgeType.REFUTED_BY, Map.of()));
            return;
        }
        String proofId = "proof:" + record.id() + ":" + identity.id();
        put(nodes, new ProvenanceNode(proofId, ProvenanceNodeType.PROOF_ATTEMPT,
            "Proof " + identity.id(), Map.of(
                "backend", "search-replay",
                "confidence", identity.proofStatus().name(),
                "durationMillis", "",
                "artifact", identity.id()
            )));
        edges.add(new ProvenanceEdge(hypothesisId, proofId, ProvenanceEdgeType.SUPPORTED_BY, Map.of()));
    }

    private static String pathNodeId(String runId, String pathId) {
        return "path:" + runId + ":" + pathId;
    }

    private static String assumptionNodeId(String runId, String assumption) {
        return "assumption:" + runId + ":" + Integer.toHexString(assumption.hashCode());
    }

    private static void put(Map<String, ProvenanceNode> nodes, ProvenanceNode node) {
        nodes.putIfAbsent(node.id(), node);
    }
}
