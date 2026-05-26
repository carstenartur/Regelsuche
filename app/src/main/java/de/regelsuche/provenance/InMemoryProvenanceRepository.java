package de.regelsuche.provenance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryProvenanceRepository implements ProvenanceRepository {
    private final Map<String, ProvenanceGraph> graphs = new LinkedHashMap<>();

    @Override
    public void save(String runId, ProvenanceGraph graph) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId is required");
        }
        graphs.put(runId, graph == null ? new ProvenanceGraph(java.util.List.of(), java.util.List.of()) : graph);
    }

    @Override
    public Optional<ProvenanceGraph> findByRunId(String runId) {
        return Optional.ofNullable(graphs.get(runId));
    }
}
