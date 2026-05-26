package de.regelsuche.provenance;

import de.regelsuche.api.searchgraph.SearchGraphRecord;
import de.regelsuche.api.searchgraph.SearchGraphRepository;
import java.util.List;
import java.util.Optional;

/** Public query facade for typed provenance graph analytics over persisted search runs. */
public final class ProvenanceQueryService {
    private final SearchGraphRepository repository;
    private final ProvenanceGraphAssembler assembler = new ProvenanceGraphAssembler();
    private final ProvenanceGraphQueries queries = new ProvenanceGraphQueries();

    public ProvenanceQueryService(SearchGraphRepository repository) {
        this.repository = repository;
    }

    public Optional<ProvenanceGraph> graphForRun(String runId) {
        return repository.findById(runId).map(assembler::assemble);
    }

    public List<ProvenanceNode> strongestHypotheses(String runId, int limit) {
        return graphForRun(runId)
            .map(graph -> queries.strongestHypotheses(graph, limit))
            .orElse(List.of());
    }

    public List<ProvenanceNode> domainRefutations(String runId, String domain) {
        return graphForRun(runId)
            .map(graph -> queries.hypothesesRefutedOnlyInDomain(graph, domain))
            .orElse(List.of());
    }

    public List<ProvenanceNode> derivationLineage(String runId, String hypothesisId) {
        return graphForRun(runId)
            .map(graph -> queries.derivationLineage(graph, hypothesisId))
            .orElse(List.of());
    }

    public List<SearchGraphRecord> runs() {
        return repository.findAll();
    }
}
