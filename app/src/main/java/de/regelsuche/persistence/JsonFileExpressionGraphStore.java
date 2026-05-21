package de.regelsuche.persistence;

import de.regelsuche.discovery.DiscoveredTransformation;
import de.regelsuche.export.DefaultTransformationExportService;
import de.regelsuche.export.DefaultTransformationImportService;
import de.regelsuche.export.ExportBundle;
import de.regelsuche.graph.GraphEdge;
import de.regelsuche.graph.InMemoryExpressionGraphStore;
import de.regelsuche.inventory.ReusableRule;
import de.regelsuche.mining.RuleCandidate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * File-backed expression graph store for the killer-demo's standard mode.
 *
 * <p>Persists the demo-relevant slices — discovered transformations, rule
 * candidates, reusable rules — to a single JSON document under {@code
 * storagePath / graph.json}. The transient search graph (nodes/edges) is
 * kept only in memory because it is fully reconstructed by re-running a
 * demo. This keeps the on-disk footprint tiny and the format easy to
 * inspect with {@code cat}.</p>
 *
 * <p>Used by {@link PersistenceContext} when
 * {@link GraphPersistenceMode#JSON_FILE} is selected (or as the documented
 * fallback for {@link GraphPersistenceMode#EMBEDDED_NEO4J} until the
 * embedded server is bundled).</p>
 */
public class JsonFileExpressionGraphStore extends InMemoryExpressionGraphStore {

    public static final String STORAGE_FILE = "graph.json";

    private final Path storageDirectory;
    private final Path file;
    private final DefaultTransformationExportService exporter = new DefaultTransformationExportService();
    private final DefaultTransformationImportService importer = new DefaultTransformationImportService();

    public JsonFileExpressionGraphStore(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        this.file = storageDirectory.resolve(STORAGE_FILE);
        try {
            Files.createDirectories(storageDirectory);
            if (Files.exists(file)) {
                hydrate();
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to initialize JSON file graph store at " + storageDirectory, ex);
        }
    }

    public Path storagePath() {
        return storageDirectory;
    }

    public Path filePath() {
        return file;
    }

    private void hydrate() throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return;
        }
        ExportBundle bundle = importer.importJson(content);
        for (DiscoveredTransformation transformation : bundle.transformations()) {
            super.saveDiscoveredTransformation(transformation);
            // Rehydrate the node set from the path's start/end so the search
            // graph view at least lists previously known expressions even
            // before a fresh demo run.
            super.saveNode(transformation.originalExpression(), transformation.originalScore().weightedTotal());
            super.saveNode(transformation.improvedExpression(), transformation.improvedScore().weightedTotal());
        }
        for (RuleCandidate candidate : bundle.ruleCandidates()) {
            super.saveRuleCandidate(candidate);
        }
        for (ReusableRule rule : bundle.reusableRules()) {
            super.saveReusableRule(rule);
        }
    }

    private synchronized void persist() {
        try {
            String json = exporter.exportJson(
                super.discoveredTransformations(),
                super.ruleCandidates(),
                super.reusableRules()
            );
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to persist graph store to " + file, ex);
        }
    }

    @Override
    public void saveNode(String expression, int complexity) {
        super.saveNode(expression, complexity);
        // Nodes are intentionally not persisted; the snapshot is rebuilt on
        // the next demo run.
    }

    @Override
    public void saveEdge(GraphEdge edge) {
        super.saveEdge(edge);
        // Edges follow the same lifecycle as nodes — transient by design.
    }

    @Override
    public void saveDiscoveredTransformation(DiscoveredTransformation transformation) {
        super.saveDiscoveredTransformation(transformation);
        persist();
    }

    @Override
    public void saveRuleCandidate(RuleCandidate candidate) {
        super.saveRuleCandidate(candidate);
        persist();
    }

    @Override
    public void saveReusableRule(ReusableRule rule) {
        super.saveReusableRule(rule);
        persist();
    }

    @Override
    public List<DiscoveredTransformation> discoveredTransformations() {
        return super.discoveredTransformations();
    }
}
